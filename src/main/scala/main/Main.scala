package main

import com.typesafe.config.ConfigFactory
import little.json.Implicits.{*, given}
import little.json.Json
import requests.{Response, readTimeout}
import upickle.default.{ReadWriter, macroRW, read, transform, write}

import scala.collection.mutable
import scala.collection.mutable.ArrayDeque
import scala.io.AnsiColor.*

object Config {
  private val conf = ConfigFactory.load()

  //#Set API Keys (from .env file with `-Dconfig.file=.env` VM option)
  val OPENAI_API_KEY = conf.getString("OPENAI_API_KEY")
  val PINECONE_API_KEY = conf.getString("PINECONE_API_KEY")
  val PINECONE_ENVIRONMENT = Option(conf.getString("PINECONE_ENVIRONMENT")).getOrElse("asia-southeast1-gcp-free")

  //#Set Variables
  val YOUR_TABLE_NAME = "test-table"
  val OBJECTIVE = "Scala 개발자로 살아보기"
  val YOUR_FIRST_TASK = "Develop a task list."
  val PINECONE_DIMENSION = 1536
}


// run with `-Dconfig.file=.env`
@main
def main(): Unit = {
  val pinecone = PineCone(Config.PINECONE_API_KEY, Config.PINECONE_ENVIRONMENT, Config.YOUR_TABLE_NAME, Config.PINECONE_DIMENSION)
  println(s"$CYAN$BOLD\n*****OBJECTIVE*****\n$RESET")
  println(Config.OBJECTIVE)

  val openAI = OpenAI(Config.OPENAI_API_KEY)
  val first = Task(1, Config.YOUR_FIRST_TASK)
  TaskService.run(first, pinecone, openAI, Config.OBJECTIVE)
}


case class Task(taskId: Int, taskName: String) {
  def print(): Unit = {
    println(s"Task $taskId: $taskName")
  }

  def withTaskId(taskId: Int): Task = {
    Task(taskId, taskName)
  }
}

object TaskService {

  def run(taskInit: Task, pineCone: PineCone, openAI: OpenAI, objective: String): Unit = {
    var taskIdCounter = 1
    val taskList = mutable.Queue[Task]()

    val agent = Agent(pineCone, openAI, objective)
    taskList.enqueue(taskInit)
    while (taskList.nonEmpty) {
      val task = taskList.dequeue()
      var thisTaskId = task.taskId

      println(s"$MAGENTA$BOLD\n*****TASK LIST*****\n$RESET")
      task.print()
      for (elem <- taskList) {
        elem.print()
      }

      //      # Step 1: Pull the first task
      println(s"$GREEN$BOLD\n*****NEXT TASK*****\n$RESET")
      print(s"${task.taskId}: ${task.taskName}")

      val result = agent.executionAgent(task.taskName)
      thisTaskId = task.taskId
      println(s"$YELLOW$BOLD\n*****TASK RESULT*****\n$RESET")
      println(result)

      //      # Step 2: Enrich result and store in Pinecone
      val resultsId = s"result_${task.taskId}"
      pineCone.upsert(resultsId, openAI.getAdaEmbedding(result), Map("task" -> task.taskName, "result" -> result))

      //      # Step 3: Create new tasks and reprioritize task list
      val newTasks = agent.taskCreationAgent(objective, Map("data" -> result), task.taskName, taskList.map(a => a.taskName).toList)

      newTasks.foreach(newTask => {
        taskIdCounter += 1
        taskList.enqueue(newTask.withTaskId(taskIdCounter))
      })

      val taskNames = taskList.map(task => task.taskName)
      val prioritized = prioritization(thisTaskId, openAI, taskNames, objective)
      taskList.clear()
      taskList.enqueueAll(prioritized)
      println(s"Running task ${task.taskId}")
      task.print()
    }
  }

  //  prioritization_agent
  def prioritization(nextTaskId: Int, openAI: OpenAI, taskNames: mutable.Queue[String], objective: String) = {
    val prompt =
      s"""You are an task prioritization AI tasked with cleaning the formatting of and reprioritizing the following tasks: ${taskNames.mkString("['", "','", "']")}. Consider the ultimate objective of your team:$objective. Do not remove any tasks. Return the result as a numbered list, like:
    #. First task
    #. Second task
    Start the task list with number $nextTaskId."""
    val response = openAI.completionCreate(prompt)
    val newTasks: List[String] = response.split("\n").toList
    val newTaskList = mutable.Queue[Task]()
    for (taskString <- newTasks) {
      val taskParts = taskString.strip().split("\\.", 2)
      if (taskParts.length == 2) {
        val taskId = taskParts(0).strip()
        val taskName = taskParts(1).strip()
        newTaskList.enqueue(Task(taskId.toInt, taskName))
      }
    }
    newTaskList
  }
}

class OpenAI(val apiKey: String) {
  private val headers = Map("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json")

  def getAdaEmbedding(text: String): List[Double] = {
    val input = text.replaceAll("\n", " ")
    val req = CreateEmbeddingRequest(input, "text-embedding-ada-002")
    val data = requests.post("https://api.openai.com/v1/embeddings", headers = headers, data = write(req), readTimeout = 30_000).data.toString()
    val response = read[CreateEmbeddingResponse](data)
    response.data.head.embedding
  }

  def completionCreate(prompt: String, temperature: Double = 0.5, maxToken: Int = 100): String = {
    val engine = "text-davinci-003"
    val req = CreateCompletionRequest(model = engine,
      prompt = prompt,
      temperature = temperature,
      max_tokens = maxToken,
      top_p = 1,
      n = 1,
      stream = false,
      frequency_penalty = 0.0, presence_penalty = 0.0
    )

//    println(s"$YELLOW$BOLD\n*****OPENAI REQUEST*****\n$RESET")
//    println(write(req))

    val data = requests.post("https://api.openai.com/v1/completions", headers = headers, data = write(req), readTimeout = 30_000).data.toString()
    val response = read[CreateCompletionResponse](data)
//    println(s"$YELLOW$BOLD\n*******RELEVANT CONTEXT******\n$RESET")
//    println(response)
    response.choices.head.text.strip()
  }
}

class PineCone(val apiKey: String, val environment: String, val tableName: String, val demension: Int) {
  val headers = Map("Api-Key" -> apiKey, "Accept" -> "text/plain", "Content-Type" -> "application/json")
  val index: IndexResponse = init()

  def init(): IndexResponse = {
    // create index if it doesn't exist
    if (getAllIndexes().contains(tableName)) {
      println("Index already exists")
    } else {
      createIndex()
    }

    getIndex()
  }

  def getAllIndexes(): List[String] = {
    val response = requests.get(s"https://controller.$environment.pinecone.io/databases", headers = headers)
    read[List[String]](response.data.toString)
  }

  def getIndex(): IndexResponse = { // indexName: String == tableName
    val url = s"https://controller.$environment.pinecone.io/databases/$tableName"
    val data = requests.get(url, headers = headers).data.toString
    read[IndexResponse](data)
  }

  def query(queryEmbedding: List[Double], topK: Int, includeMetadata: Boolean): QueryResponse = {
    val query = QueryRequest(queryEmbedding, topK, includeMetadata)

    val data = requests.post(s"https://${index.host}/query", headers = headers, data = write(query)).data
    read[QueryResponse](data.toString)
  }

  def upsert(resultsId: String, adaEmbedding: List[Double], taskResult: Map[String, String]): Unit = {
    val body = VectorRequest(List(PineVector(resultsId, adaEmbedding, taskResult)))
//    println(s"$YELLOW$BOLD\n*****PINECONE REQUEST*****\n$RESET")
//    println(write(body))
    requests.post(s"https://${index.host}/vectors/upsert", headers = headers, data = write(body)).data
  }

  private def createIndex(): Response = {
    val body = CreateIndexRequest("cosine", "p1", tableName, demension, 1, 1)
    val url = s"https://controller.$environment.pinecone.io/databases"

    try {
      requests.post(url, headers = headers, data = write(body))
    } catch {
      case e: Exception => println(e); throw e
    }
  }
}

class Agent(val pineCone: PineCone, val openAI: OpenAI, val objective: String) {
  def executionAgent(task: String): String = {
    val context = contextAgent(query = objective, n = 5)

    openAI.completionCreate(s"You are an AI who performs one task based on the following objective: $objective. Your task: $task\nResponse:",
      temperature = 0.7,
      maxToken = 2000)
  }

  def contextAgent(query: String, n: Int): List[String] = {
    val queryEmbedding = openAI.getAdaEmbedding(query)
    val results = pineCone.query(queryEmbedding, topK = n, includeMetadata = true)
    results.matches.sortBy(_.score).reverse.map(_.metadata("task"))
  }

  def taskCreationAgent(objective: String, result: Map[String, Any], taskDescription: String, taskList: List[String]): List[Task] = {

    val prompt = s"You are an task creation AI that uses the result of an execution agent to create new tasks with the following objective: $objective, The last completed task has the result: ${toJson(result)}. This result was based on this task description: ${taskDescription}. These are incomplete tasks: ${taskList.mkString(", ")}. Based on the result, create new tasks to be completed by the AI system that do not overlap with incomplete tasks. Return the tasks as an array."
    val response = openAI.completionCreate(prompt = prompt)
    val new_tasks = response.split("\n").toList
    new_tasks.map(name => Task(0, name))
  }

  def toJson(query: Any): String = query match {
    case m: Map[String, Any] => s"{${m.map(toJson(_)).mkString(",")}}"
    case t: (String, Any) => s""""${t._1}":${toJson(t._2)}"""
    case ss: Seq[Any] => s"""[${ss.map(toJson(_)).mkString(",")}]"""
    case s: String => s""""$s""""
    case null => "null"
    case _ => query.toString
  }
}