package dropnext.dss.handler

import dropnext.dss.GraphqlClientCache
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.dss.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dss.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dss.dto.Shipment
import dropnext.dss.lib.dss.dto.ShipmentLineItem
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateResponse
import dropnext.dss.lib.fulfillment.DssFulfillmentService
import dropnext.dss.lib.json.AppJson
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.testDssAppConfig
import dropnext.dss.testing.fake.testShopifyConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DssHttpHandlersTest {

  @Volatile
  private var current: DssHttpHandlers? = null

  private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
  private lateinit var baseUrl: String
  private lateinit var client: HttpClient

  @BeforeAll
  fun startServer() {
    server = embeddedServer(CIO, port = 0) {
      install(ServerContentNegotiation) { json(AppJson) }
      routing {
        // Routes match production wiring in DssRouting.kt (path names intentionally
        // do not reflect body types — see DssRouting KDoc).
        post(DssPaths.TRACKING_UPDATE) { dispatch { handleSyncShipments(call) } }
        post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) { dispatch { handleTrackingUpdate(call) } }
        post(DssPaths.TRACKING_UPDATES) { dispatch { handleTrackingUpdate(call) } }
        put(DssPaths.STORES_API_KEY) { dispatch { handlePutStoreApiKey(call) } }
      }
    }
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    baseUrl = "http://localhost:$port"
    client = HttpClient(OkHttp) {
      engine { config { connectTimeout(2, TimeUnit.SECONDS); readTimeout(5, TimeUnit.SECONDS) } }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
      install(ClientContentNegotiation) { json(AppJson) }
    }
  }

  @AfterAll
  fun stopServer() {
    client.close()
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  @AfterTest
  fun clearHandlers() {
    current = null
  }

  private suspend fun io.ktor.server.routing.RoutingContext.dispatch(
    block: suspend DssHttpHandlers.() -> Unit,
  ) {
    val h = current
    if (h == null) call.respondText("no handlers set", status = HttpStatusCode.InternalServerError)
    else h.block()
  }

  // ---------- internal secret enforcement ----------

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() = runBlocking {
    current = handlers(internalSecret = "y".repeat(32))
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("unauthorized" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments accepts when internal secret matches`() = runBlocking {
    current = handlers(internalSecret = "y".repeat(32), sandboxFakeShopify = true)
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      header("X-DSS-Internal-Secret", "y".repeat(32))
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.OK)
  }

  // ---------- validation ----------

  @Test
  fun `sync-shipments returns 400 for non-positive shopify_order_id`() = runBlocking {
    current = handlers()
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifyOrderId = 0))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_order_id" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = runBlocking {
    current = handlers()
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  // ---------- sandbox short-circuit ----------

  @Test
  fun `sync-shipments returns stub response when sandboxFakeShopify is true`() = runBlocking {
    current = handlers(sandboxFakeShopify = true)
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.OK)
    val resp = r.body<SyncShipmentsWithFulfillmentsResponse>()
    assert(resp.newFulfillmentIds.single() == 9_000_000_000_000_001L)
  }

  @Test
  fun `tracking-update returns stub response when sandboxFakeShopify is true`() = runBlocking {
    current = handlers(sandboxFakeShopify = true)
    val r = client.post("$baseUrl${DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS}") {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.OK)
    val resp = r.body<TrackingUpdateResponse>()
    assert(resp.fulfillmentEventId == 9_000_000_000_000_001L)
  }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when no token in header or env`() = runBlocking {
    current = handlers(shopAccessTokens = ConcurrentHashMap())
    val r = client.post("$baseUrl${DssPaths.TRACKING_UPDATE}") {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.bodyAsText())
  }

  // ---------- handlePutStoreApiKey ----------

  @Test
  fun `PUT stores api-key caches token in shopAccessTokens map`() = runBlocking {
    val tokens = dropnext.dss.lib.dss.ShopAccessTokenCache()
    current = handlers(shopTokens = tokens)
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "acme",
          apiKey = "shpat_new_token",
          shopifyShopId = 99L,
        ),
      )
    }
    assert(r.status == HttpStatusCode.OK)
    val resp = r.body<PutShopAccessTokenResponse>()
    assert(resp.shop == "acme.myshopify.com")
    assert(tokens["acme.myshopify.com"] == "shpat_new_token")
  }

  @Test
  fun `PUT stores api-key forwards normalised request to monolith`() = runBlocking {
    val tokens = dropnext.dss.lib.dss.ShopAccessTokenCache()
    val fake = FakeMonolithService()
    current = handlers(shopTokens = tokens, monolith = fake)
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "acme",
          apiKey = "shpat_new",
          shopifyShopId = 99L,
        ),
      )
    }
    assert(r.status == HttpStatusCode.OK)
    assert(tokens["acme.myshopify.com"] == "shpat_new")
    assert(fake.putStoreApiKeyCallCount == 1)
    val forwarded = fake.lastPutStoreApiKey
    assert(forwarded != null)
    assert(forwarded!!.shopifySubdomain == "acme")
    assert(forwarded.shopifyShopId == 99L)
    assert(forwarded.apiKey == "shpat_new")
  }

  @Test
  fun `PUT stores api-key does not call monolith when none is configured`() = runBlocking {
    val tokens = dropnext.dss.lib.dss.ShopAccessTokenCache()
    current = handlers(shopTokens = tokens, monolith = null)
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "acme",
          apiKey = "shpat_local",
          shopifyShopId = null,
        ),
      )
    }
    assert(r.status == HttpStatusCode.OK)
    assert(tokens["acme.myshopify.com"] == "shpat_local")
  }

  @Test
  fun `PUT stores api-key still caches token when monolith returns an error`() = runBlocking {
    val tokens = dropnext.dss.lib.dss.ShopAccessTokenCache()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    current = handlers(shopTokens = tokens, monolith = fake)
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "acme",
          apiKey = "shpat_x",
          shopifyShopId = null,
        ),
      )
    }
    assert(r.status == HttpStatusCode.OK)
    assert(tokens["acme.myshopify.com"] == "shpat_x")
    assert(fake.putStoreApiKeyCallCount == 1)
  }

  @Test
  fun `PUT stores api-key rejects invalid shopify_subdomain as 400`() = runBlocking {
    current = handlers()
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "!!invalid!!",
          apiKey = "shpat",
          shopifyShopId = null,
        ),
      )
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  @Test
  fun `PUT stores api-key requires internal secret when configured`() = runBlocking {
    current = handlers(internalSecret = "z".repeat(32))
    val r = client.put("$baseUrl${DssPaths.STORES_API_KEY}") {
      contentType(ContentType.Application.Json)
      setBody(
        PutShopAccessTokenRequest(
          shopifySubdomain = "acme",
          apiKey = "shpat_x",
          shopifyShopId = null,
        ),
      )
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  // ---------- helpers ----------

  private fun handlers(
    internalSecret: String? = null,
    sandboxFakeShopify: Boolean = false,
    shopAccessTokens: MutableMap<String, String> = ConcurrentHashMap<String, String>().apply {
      put("acme.myshopify.com", "shpat_env_token")
    },
    monolith: FakeMonolithService? = null,
    shopTokens: dropnext.dss.lib.dss.ShopAccessTokenCache = dropnext.dss.lib.dss.ShopAccessTokenCache(shopAccessTokens),
  ): DssHttpHandlers {
    val shopify = testShopifyConfig()
    val dssConfig = testDssAppConfig(
      shopify = shopify,
      dssInternalSecret = internalSecret,
      sandboxFakeShopify = sandboxFakeShopify,
    )
    val cache = GraphqlClientCache(client)
    return DssHttpHandlers(
      shopifyConfig = shopify,
      dssConfig = dssConfig,
      gqlClientCache = cache,
      fulfillmentService = DssFulfillmentService(),
      monolithService = monolith,
      shopTokens = shopTokens,
    )
  }

  private fun validSyncRequest(): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = listOf(
        Shipment(
          trackingNumber = "1Z999",
          carrier = "UPS",
          trackingUrl = null,
          lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
        ),
      ),
    )

  private fun validTrackingRequest(): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = "1Z999",
      status = "in_transit",
      happenedAt = "2026-04-02T08:30:00Z",
      message = null,
    )
}
