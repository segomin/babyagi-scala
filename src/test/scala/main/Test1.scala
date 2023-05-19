package main

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.{ReadWriter, macroRW, read, transform, write}

import scala.io.AnsiColor.*


class MainSpec extends AnyFunSuite {
  val pinecone = PineCone(Config.PINECONE_API_KEY, Config.PINECONE_ENVIRONMENT, Config.YOUR_TABLE_NAME, Config.PINECONE_DIMENSION)
  test("run babyagi") {
    main()
  }

  test("get all pinecone indexes") {
    val actual = pinecone.getAllIndexes()
    println(actual.toString())
  }

  test("get pinecone index") {
    val actual = pinecone.getIndex()
    println(actual)
  }
}