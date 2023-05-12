package main

import com.typesafe.config.ConfigFactory
import little.json.Implicits.{*, given}
import little.json.Json
import Config.OBJECTIVE
import requests.Response
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
  val OBJECTIVE = "Solve world hunger."
  val YOUR_FIRST_TASK = "Develop a task list."
  val PINECONE_DIMENSION = 1536
}


@main
def main(): Unit = {
  val pinecone = PineCone(Config.PINECONE_API_KEY, Config.PINECONE_ENVIRONMENT, Config.YOUR_TABLE_NAME, Config.PINECONE_DIMENSION)
  println(s"$CYAN$BOLD\n*****OBJECTIVE*****\n$RESET")
  println(Config.OBJECTIVE)

  pinecone.init()
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

  def run(task: Task, pineCone: PineCone, openAI: OpenAI, objective: String): Unit = {
    var taskIdCounter = 1
    val taskList = mutable.Queue[Task]()

    val agent = Agent(pineCone, openAI, objective)
    taskList.enqueue(task)
    while (taskList.nonEmpty) {
      println(s"$MAGENTA$BOLD\n*****TASK LIST*****\n$RESET")
      for (elem <- taskList) {
        elem.print()
      }

      //      # Step 1: Pull the first task
      val task = taskList.dequeue()
      println(s"$GREEN$BOLD\n*****NEXT TASK*****\n$RESET")
      print(s"${task.taskId}: ${task.taskName}")

      val result = agent.executionAgent(task.taskName)
      println(s"$YELLOW$BOLD\n*****TASK RESULT*****\n$RESET")
      println(result)

      val newTasks = agent.taskCreationAgent(objective, Map("data" -> result) , task.taskName, taskList.map(a => a.taskName).toList)

      newTasks.foreach(newTask => {
        taskIdCounter += 1
        taskList.enqueue(newTask.withTaskId(taskIdCounter))
      })

      val taskNames = taskList.map(task => task.taskName)
      val prioritized = prioritization(task.taskId + 1, openAI, taskNames, objective)
      taskList.clear()
      taskList.enqueueAll(prioritized)
      println(s"Running task ${task.taskId}")
      task.print()
    }
  }

  //  prioritization_agent
  def prioritization(nextTaskId: Int, openAI: OpenAI, taskNames: mutable.Queue[String], objective: String) = {
    val prompt =
      s"""You are an task prioritization AI tasked with cleaning the formatting of and reprioritizing the following tasks: $taskNames. Consider the ultimate objective of your team:$objective. Do not remove any tasks. Return the result as a numbered list, like:
    #. First task
    #. Second task
    Start the task list with number $nextTaskId."""
    val response = openAI.completionCreate(prompt)
    val newTasks: List[String] = response.split("\n").toList
    val newTaskList = mutable.Queue[Task]()
    for (taskString <- newTasks) {
      val taskParts = taskString.strip().split(".", 1)
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
    val data = requests.post("https://api.openai.com/v1/embeddings", headers = headers, data = write(req)).data.toString()
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

    val data = requests.post("https://api.openai.com/v1/completions", headers = headers, data = write(req)).data.toString()
    val response = read[CreateCompletionResponse](data)
    response.choices.head.text.strip()
  }
}

class PineCone(val apiKey: String, val environment: String, val tableName: String, val demension: Int) {
  val headers = Map("Api-Key" -> apiKey, "Accept" -> "text/plain", "Content-Type" -> "application/json")

  def init(): Unit = {
    // create index if it doesn't exist
    if (getAllIndexes().contains(tableName)) {
      println("Index already exists")
    } else {
      createIndex()
    }
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

  def query(index: IndexResponse, queryEmbedding: List[Double], topK: Int, includeMetadata: Boolean): QueryResponse = {
    val query = QueryRequest(queryEmbedding, topK, includeMetadata)

    val data = requests.post(s"https://${index.host}/query", headers = headers, data = write(query)).data
    read[QueryResponse](data.toString)
  }

  private def createIndex(): Response = {
    val body = CreateIndexRequest("cosine", "p1", tableName, demension, 1, 1)
    val url = s"https://controller.$environment.pinecone.io/databases" // val url = s"https://httpbin.org/post"

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
    val indexRsp = pineCone.getIndex()
    val results = pineCone.query(indexRsp, queryEmbedding, topK = n, includeMetadata = true)
    results.matches.sortBy(_.score).reverse.map(_.metadata("task"))
  }

  def taskCreationAgent(objective: String, result: Map[String, Any], taskDescription: String, taskList: List[String]): List[Task] = {
    val prompt = s"You are an task creation AI that uses the result of an execution agent to create new tasks with the following objective: $objective, The last completed task has the result: ${result}. This result was based on this task description: ${taskDescription}. These are incomplete tasks: ${taskList.mkString(", ")}. Based on the result, create new tasks to be completed by the AI system that do not overlap with incomplete tasks. Return the tasks as an array."
    val response = openAI.completionCreate(prompt = prompt)
    val new_tasks = response.split("\n").toList
    new_tasks.map(name => Task(0, name))
  }
}