package main

import upickle.default.{ReadWriter, macroRW}

case class CreateIndexRequest(metric: String,
                              pod_type: String,
                              name: String,
                              dimension: Int,
                              pods: Int,
                              replicas: Int)

object CreateIndexRequest {
  implicit val rw: ReadWriter[CreateIndexRequest] = macroRW
}

/**
 * IndexResponse
 */
case class IndexResponse(database: Database, status: Status) {
  lazy val host = s"${status.host}"
  lazy val table = database.name
}
case class Database(name: String = null, metric: String = null, dimension: Int, replicas: Int, shards: Int, pods: Int)
case class Status(host: String, port: Int, state: String, ready: Boolean) // host: "TABLE_NAME-YOUR_PROJECT.svc.YOUR_ENVIRONMENT.pinecone.io"

object IndexResponse {
  implicit val rw: ReadWriter[IndexResponse] = macroRW
}
object Database {
  implicit val rw: ReadWriter[Database] = macroRW
}
object Status {
  implicit val rw: ReadWriter[Status] = macroRW
}

/**
 * QueryRequest
 */
case class QueryRequest(vector: List[Double], topK: Int, includeMetadata: Boolean) derives ReadWriter

val aaa =
  """
    |{"namespace":"example-namespace",
    |"topK":10,"filter":{"genre":{"$in":["comedy","documentary","drama"]},
    |"year":{"$eq":2019}},
    |"includeValues":true,
    |"includeMetadata":true,
    |"vector":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8],
    |"sparseVector":{"indices":[1,312,822,14,980],
    |"values":[0.1,0.2,0.3,0.4,0.5]},
    |"id":"example-vector-1"}
    |""".stripMargin
/**
 * QueryResponse
 */
case class QueryResponse(matches: List[QueryItem], namespace: String) derives ReadWriter
case class QueryItem(id: String, score: Double, values: List[Double], metadata: Map[String, String]) derives ReadWriter

//object QueryResponse {
//  implicit val rw: ReadWriter[QueryResponse] = macroRW
//}
//object QueryItem {
//  implicit val rw: ReadWriter[QueryItem] = macroRW
//}

/**
  * OpenAI Create embeddings
  *  https://platform.openai.com/docs/api-reference/embeddings/create
  */
case class CreateEmbeddingRequest(input: String, model: String)
object CreateEmbeddingRequest {
  implicit val rw: ReadWriter[CreateEmbeddingRequest] = macroRW
}

case class CreateEmbeddingResponse(`object`: String, data: List[Embedding], model: String)
case class Embedding(`object`: String, embedding: List[Double], index: Int)
object CreateEmbeddingResponse {
  implicit val rw: ReadWriter[CreateEmbeddingResponse] = macroRW
}
object Embedding {
  implicit val rw: ReadWriter[Embedding] = macroRW
}
//{
//  "data": [
//    {
//      "object": "embedding",
//      "embedding": [
//        0.0023064255,
//        -0.009327292,
//        .... (1536 floats total for ada-002)
//        -0.0028842222,
//      ],
//      "index": 0
//    }
//  ],
//  "model": "text-embedding-ada-002",
//  ...
//}

/**
  * OpenAI Create Completion
  *  https://platform.openai.com/docs/api-reference/completions/create
  */
case class CreateCompletionRequest(model: String,
                                   prompt: String,
                                   max_tokens: Int,
                                   temperature: Double,
                                   top_p: Int,
                                   n: Int,
                                   stream: Boolean,
                                   frequency_penalty: Double,
                                   presence_penalty: Double)
//{
//  "model": "text-davinci-003",
//  "prompt": "Say this is a test",
//  "max_tokens": 7,
//  "temperature": 0,
//  "top_p": 1,
//  "n": 1,
//  "stream": false,
//  "logprobs": null,
//  "stop": "\n"
//}
case class CreateCompletionResponse(id: String, `object`: String, created: Long, model: String, choices: List[Choice])
case class Choice(text: String, index: Int, finish_reason: String)
object CreateCompletionRequest {
  implicit val rw: ReadWriter[CreateCompletionRequest] = macroRW
}
object CreateCompletionResponse {
  implicit val rw: ReadWriter[CreateCompletionResponse] = macroRW
}
object Choice {
  implicit val rw: ReadWriter[Choice] = macroRW
}
//{
//  "id": "cmpl-uqkvlQyYK7bGYrRHQ0eXlWi7",
//  "object": "text_completion",
//  "created": 1589478378,
//  "model": "text-davinci-003",
//  "choices": [
//    {
//      "text": "\n\nThis is indeed a test",
//      "index": 0,
//      "logprobs": null,
//      "finish_reason": "length"
//    }
//  ],
//  "usage": {
//    "prompt_tokens": 5,
//    "completion_tokens": 7,
//    "total_tokens": 12
//  }
//}