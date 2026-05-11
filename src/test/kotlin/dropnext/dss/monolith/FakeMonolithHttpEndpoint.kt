package dropnext.dss.monolith

import dropnext.dss.lib.dss.dto.CreateOrderResponse
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsResponse
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.dto.StoreResponse
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyResponse
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsResponse
import dropnext.dss.lib.dss.dto.VariantIdsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

data class FakeMonolithConfig(
  val ordersResponseStatus: HttpStatusCode = HttpStatusCode.OK,
  val requiredBearerToken: String? = null,
  val storeApiKeyResponseStatus: HttpStatusCode = HttpStatusCode.OK,
  val storeNotFound: Boolean = false,
  val upsertVariantsResponseStatus: HttpStatusCode = HttpStatusCode.OK,
  val variantIdsResponseStatus: HttpStatusCode = HttpStatusCode.OK,
  val knownVariantIds: List<Long> = emptyList(),
  val deleteVariantsResponseStatus: HttpStatusCode = HttpStatusCode.OK,
)

/**
 * In-process fake monolith server for HTTP-wire tests. Implements all routes the DSS calls on the monolith:
 * - POST /orders
 * - PUT /stores/api-key
 * - GET /stores
 * - POST /product-variants
 * - GET /product-variants
 * - DELETE /product-variants
 */
class FakeMonolithHttpEndpoint(
  private val ordersPath: String = "/orders",
  initialConfig: FakeMonolithConfig = FakeMonolithConfig(),
) {
  private val freePort: Int = ServerSocket(0).use { it.localPort }
  val baseUrl: String = "http://127.0.0.1:$freePort"

  private val configRef = AtomicReference(initialConfig)
  val ordersPostCount: AtomicInteger = AtomicInteger(0)
  val storeApiKeyPutCount: AtomicInteger = AtomicInteger(0)
  val upsertVariantsPostCount: AtomicInteger = AtomicInteger(0)
  val deleteVariantsCount: AtomicInteger = AtomicInteger(0)

  private val lastOrderJsonRef = AtomicReference<String?>(null)
  private val lastAuthorizationHeaderRef = AtomicReference<String?>(null)
  private val lastStoreApiKeyJsonRef = AtomicReference<String?>(null)
  private val lastUpsertVariantsJsonRef = AtomicReference<String?>(null)
  private val lastDeleteVariantsJsonRef = AtomicReference<String?>(null)

  val lastOrderJson: String? get() = lastOrderJsonRef.get()
  val lastAuthorizationHeader: String? get() = lastAuthorizationHeaderRef.get()
  val lastStoreApiKeyJson: String? get() = lastStoreApiKeyJsonRef.get()
  val lastUpsertVariantsJson: String? get() = lastUpsertVariantsJsonRef.get()
  val lastDeleteVariantsJson: String? get() = lastDeleteVariantsJsonRef.get()

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

      put("/stores/api-key") {
        val cfg = configRef.get()
        val text = runCatching { call.receiveText() }.getOrNull()
        if (text == null) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "empty body"))
          return@put
        }
        lastStoreApiKeyJsonRef.set(text)
        storeApiKeyPutCount.incrementAndGet()
        val code = cfg.storeApiKeyResponseStatus
        if (code != HttpStatusCode.OK) {
          call.respond(code, ErrorResponse(error = "simulated failure"))
          return@put
        }
        val req = runCatching { json.decodeFromString(UpdateStoreApiKeyRequest.serializer(), text) }.getOrNull()
        call.respond(UpdateStoreApiKeyResponse(storeId = 1L))
      }

      get("/stores") {
        val cfg = configRef.get()
        val subdomain = call.request.queryParameters["shopify_subdomain"]
        if (subdomain.isNullOrBlank()) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "missing shopify_subdomain"))
          return@get
        }
        if (cfg.storeNotFound) {
          call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Store not found."))
          return@get
        }
        call.respond(
          StoreResponse(
            storeId = 1L,
            shopifyShopId = 1234567890L,
            apiKey = "shpat_fake",
          ),
        )
      }

      post("/product-variants") {
        val cfg = configRef.get()
        val text = runCatching { call.receiveText() }.getOrNull()
        if (text == null) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "empty body"))
          return@post
        }
        lastUpsertVariantsJsonRef.set(text)
        upsertVariantsPostCount.incrementAndGet()
        val code = cfg.upsertVariantsResponseStatus
        if (code != HttpStatusCode.OK) {
          call.respond(code, ErrorResponse(error = "simulated failure"))
          return@post
        }
        val req = runCatching { json.decodeFromString(UpsertProductVariantsRequest.serializer(), text) }.getOrNull()
        val count = req?.productVariants?.size ?: 0
        call.respond(UpsertProductVariantsResponse(upserted = count))
      }

      get("/product-variants") {
        val cfg = configRef.get()
        val subdomain = call.request.queryParameters["shopify_subdomain"]
        if (subdomain.isNullOrBlank()) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "missing shopify_subdomain"))
          return@get
        }
        if (cfg.storeNotFound) {
          call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Store not found."))
          return@get
        }
        if (cfg.variantIdsResponseStatus != HttpStatusCode.OK) {
          call.respond(cfg.variantIdsResponseStatus, ErrorResponse(error = "simulated failure"))
          return@get
        }
        call.respond(VariantIdsResponse(productVariantIds = cfg.knownVariantIds))
      }

      delete("/product-variants") {
        val cfg = configRef.get()
        val text = runCatching { call.receiveText() }.getOrNull()
        if (text == null) {
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "empty body"))
          return@delete
        }
        lastDeleteVariantsJsonRef.set(text)
        deleteVariantsCount.incrementAndGet()
        val code = cfg.deleteVariantsResponseStatus
        if (code != HttpStatusCode.OK) {
          call.respond(code, ErrorResponse(error = "simulated failure"))
          return@delete
        }
        val req = runCatching { json.decodeFromString(DeleteProductVariantsRequest.serializer(), text) }.getOrNull()
        val count = req?.productVariantIds?.size ?: 0
        call.respond(DeleteProductVariantsResponse(deleted = count))
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
