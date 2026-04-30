package dropnext.dss.monolith

import dropnext.dss.lib.dss.dto.CreateOrderResponse
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

data class FakeMonolithConfig(
  val ordersResponseStatus: HttpStatusCode = HttpStatusCode.OK,
  val requiredBearerToken: String? = null,
)

/**
 * In-process fake monolith for tests. Implements `POST /orders` the same way [com.example.lib.monolith.HttpMonolithClient] calls it.
 */
class FakeMonolithHttpEndpoint(
  private val ordersPath: String = "/orders",
  initialConfig: FakeMonolithConfig = FakeMonolithConfig(),
) {
  private val freePort: Int = ServerSocket(0).use { it.localPort }
  val baseUrl: String = "http://127.0.0.1:$freePort"

  private val configRef = AtomicReference(initialConfig)
  val ordersPostCount: AtomicInteger = AtomicInteger(0)
  private val lastOrderJsonRef = AtomicReference<String?>(null)
  private val lastAuthorizationHeaderRef = AtomicReference<String?>(null)

  val lastOrderJson: String? get() = lastOrderJsonRef.get()
  val lastAuthorizationHeader: String? get() = lastAuthorizationHeaderRef.get()

  fun setConfig(config: FakeMonolithConfig) {
    configRef.set(config)
  }

  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }

  private val server: EmbeddedServer<*, *>

  init {
    server =
      embeddedServer(
        factory = CIO,
        port = freePort,
        host = "127.0.0.1",
        module = { fakeMonolithModule() },
      )
  }

  private fun Application.fakeMonolithModule() {
    install(ContentNegotiation) {
      json(json)
    }
    routing {
      post(ordersPath) {
        val cfg = configRef.get()
        val bearer = call.request.headers["Authorization"]
        lastAuthorizationHeaderRef.set(bearer)
        val need = cfg.requiredBearerToken
        if (need != null && bearer != "Bearer $need") {
          call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
          return@post
        }
        val text = runCatching { call.receiveText() }.getOrNull()
        if (text == null) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "empty body"))
          return@post
        }
        lastOrderJsonRef.set(text)
        ordersPostCount.incrementAndGet()
        val code = cfg.ordersResponseStatus
        if (code != HttpStatusCode.OK) {
          call.respond(code, ErrorResponse(error = "simulated monolith failure"))
          return@post
        }
        val orderId = runCatching {
          json.decodeFromString(CreateShopifyOrderRequest.serializer(), text).shopifyOrderId
        }.getOrNull() ?: 0L
        call.respond(CreateOrderResponse(shopifyOrderId = orderId))
      }
    }
  }

  fun start() {
    server.start(wait = false)
  }

  fun stop() {
    server.stop(1_000, 2_000)
  }
}
