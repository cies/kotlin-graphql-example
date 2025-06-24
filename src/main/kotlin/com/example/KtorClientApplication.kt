package com.example

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.expediagroup.graphql.generated.TestQuery
//import com.expediagroup.graphql.generated.AddObjectMutation
//import com.expediagroup.graphql.generated.EntitiesQuery
//import com.expediagroup.graphql.generated.ExampleQuery
//import com.expediagroup.graphql.generated.HelloWorldQuery
//import com.expediagroup.graphql.generated.RetrieveObjectQuery
//import com.expediagroup.graphql.generated.UpdateObjectMutation
//import com.expediagroup.graphql.generated.entitiesquery.Product
//import com.expediagroup.graphql.generated.inputs.BasicObjectInput
//import com.expediagroup.graphql.generated.inputs.SimpleArgumentInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.TimeUnit

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
        url = URI("https://beta.pokeapi.co/graphql/v1beta").toURL(),
        httpClient = httpClient
    )

    println("TestQuery example")
    runBlocking {
        val testQuery = TestQuery()
        val testQueryResult = client.execute(testQuery)
        println("\tfirst result: ${testQueryResult.data}")

        val testQueryResultImplicit: GraphQLClientResponse<TestQuery.Result> = client.execute(testQuery)
        println("\tsecond result: $testQueryResultImplicit")

        val results = client.execute(listOf(TestQuery()))
        val resultsNoParam = results[0].data as? TestQuery.Result
        println("\tthird result: $resultsNoParam")
    }

    client.close()
}
