package com.example

import com.example.graphql.generated.TestQuery
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.logging.*
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

fun main() {
  val httpClient = HttpClient(engineFactory = OkHttp) {
    engine {
      config {
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
      }
    }
    install(Logging) {
      logger = Logger.DEFAULT
      level = LogLevel.HEADERS
    }
  }
  val client = GraphQLKtorClient(
    url = URI("https://shopify.dev/admin-graphql-direct-proxy/2024-10").toURL(),
    // url = URI("https://beta.pokeapi.co/graphql/v1beta").toURL(), // much smaller schema (good for testing)
    httpClient = httpClient
  )

  println("TestQuery example")
  runBlocking {
    val testQuery = TestQuery()
    val testQueryResult = client.execute(testQuery)
    println("\tfirst result: ${testQueryResult.data}")

    val testQueryResultImplicit: GraphQLClientResponse<TestQuery.Result> = client.execute(testQuery)
    println("\tsecond result: $testQueryResultImplicit")

    val results = client.execute(listOf(testQuery))
    val resultsNoParam = results[0].data as? TestQuery.Result
    println("\tthird result: $resultsNoParam")
  }

  client.close()
}
