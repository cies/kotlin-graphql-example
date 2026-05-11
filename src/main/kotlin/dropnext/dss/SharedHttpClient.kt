package dropnext.dss

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit

fun createSharedHttpClient(): HttpClient =
  HttpClient(OkHttp) {
    engine {
      config {
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(120, TimeUnit.SECONDS)
        writeTimeout(120, TimeUnit.SECONDS)
      }
    }
    install(HttpTimeout) {
      requestTimeoutMillis = 120_000
      connectTimeoutMillis = 15_000
      socketTimeoutMillis = 120_000
    }
    install(ContentNegotiation) {
      json(AppJson)
    }
    // No Logging plugin: avoids accidentally logging Authorization headers or JSON bodies to monolith/Shopify.
  }
