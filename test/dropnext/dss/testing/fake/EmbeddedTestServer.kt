package dropnext.dss.testing.fake

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Spin up an embedded CIO server with [configure] applied to its Application module, then run
 * [block] with the resolved base URL and a short-timeout HttpClient. Server and client are torn
 * down even on test failure.
 */
fun <R> withEmbeddedServer(
  configure: Application.() -> Unit,
  block: suspend (baseUrl: String, client: HttpClient) -> R,
): R = runBlocking {
  val server = embeddedServer(CIO, port = 0, module = configure).start(wait = false)
  val port = server.engine.resolvedConnectors().first().port
  val client = HttpClient(OkHttp) {
    engine {
      config {
        connectTimeout(2, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
        writeTimeout(5, TimeUnit.SECONDS)
      }
    }
    install(HttpTimeout) {
      requestTimeoutMillis = 5_000
      connectTimeoutMillis = 2_000
      socketTimeoutMillis = 5_000
    }
  }
  try {
    block("http://localhost:$port", client)
  } finally {
    client.close()
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }
}
