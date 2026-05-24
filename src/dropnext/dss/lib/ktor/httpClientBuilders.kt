package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
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

/**
 * Derives a monolith-specific [HttpClient] that retries on connection/IO errors with exponential
 * backoff. Shopify Graphql and OAuth traffic continue to use the base [createSharedHttpClient] so
 * non-idempotent mutations are never duplicated. The OkHttp engine and connection pool are shared.
 */
fun createMonolithHttpClient(baseHttpClient: HttpClient): HttpClient = baseHttpClient.config {
  install(HttpRequestRetry) {
    maxRetries = 3
    retryOnExceptionIf { _, cause -> cause is IOException }
    exponentialDelay(base = 2.0, maxDelayMs = 4_000)
  }
}
