package dropnext.dss.testutil.fake

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.toMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/**
 * Recording embedded CIO server that fakes the monolith for [HttpMonolithService] tests.
 * Use [enqueue] to set the next response; every received request is appended to [requests].
 */
class FakeMonolithHttpServer : RecordingFake {

  data class RecordedRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val body: String,
  ) {
    fun authorization(): String? = headers["Authorization"]?.firstOrNull()
    fun contentType(): String? = headers["Content-Type"]?.firstOrNull()
  }

  data class CannedResponse(
    val status: HttpStatusCode,
    val body: String,
    val contentType: ContentType = ContentType.Application.Json,
  )

  private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = 0) {
      routing {
        route("{...}") {
          handle {
            val raw = call.receiveText()
            val recorded =
              RecordedRequest(
                method = call.request.httpMethod.value,
                path = call.request.local.uri.substringBefore('?'),
                query = call.request.queryParameters.toMap(),
                headers = call.request.headers.toMap(),
                body = raw,
              )
            requests.add(recorded)
            val response = nextResponse ?: defaultResponse
            call.respondText(response.body, response.contentType, response.status)
          }
        }
      }
    }

  // Written by the server's own request thread, read by the test thread.
  val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

  @Volatile
  var nextResponse: CannedResponse? = null

  var defaultResponse: CannedResponse =
    CannedResponse(HttpStatusCode.OK, "{}")

  fun start(): Int {
    server.start(wait = false)
    return runBlocking { server.engine.resolvedConnectors().first().port }
  }

  fun stop() {
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  override fun clear() {
    requests.clear()
    nextResponse = null
    defaultResponse = CannedResponse(HttpStatusCode.OK, "{}")
  }

  fun enqueue(status: HttpStatusCode, body: String) {
    nextResponse = CannedResponse(status, body)
  }
}
