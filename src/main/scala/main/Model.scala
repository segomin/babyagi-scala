package main

import upickle.default.{ReadWriter, macroRW}

case class CreateIndexRequest(metric: String,
                              pod_type: String,
                              name: String,
                              dimension: Int,
                              pods: Int = 1,
                              replicas: Int = 1)

object CreateIndexRequest {
  implicit val rw: ReadWriter[CreateIndexRequest] = macroRW
}

/**
 * IndexResponse
 */
case class IndexResponse(database: Database, status: Status) {
  lazy val host = s"${status.host}:${status.port}"
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
case class QueryRequest(vector: List[Double], topK: Int = 5, includeMetadata: Boolean = true)

object QueryRequest {
  implicit val rw: ReadWriter[QueryRequest] = macroRW
}

/**
 * QueryResponse
 */
case class QueryResponse(matches: List[QueryItem], namespace: String)
case class QueryItem(id: String, score: Double, values: List[Double], metadata: Map[String, String])

object QueryResponse {
  implicit val rw: ReadWriter[QueryResponse] = macroRW
}
object QueryItem {
  implicit val rw: ReadWriter[QueryItem] = macroRW
}

