package dropnext.dss

import dropnext.dss.lib.json.AppJson
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import java.util.concurrent.TimeUnit


fun createSharedHttpClient(): HttpClient = HttpClient(OkHttp) {
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
