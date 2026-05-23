package dropnext.dss.handler

import dropnext.dss.lib.shopify.graphql.GraphqlClientCache
import dropnext.dss.lib.ktor.createSharedHttpClient
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.graphql.fulfillment.DssFulfillmentService
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installDssRoutes
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.testDssAppConfig
import dropnext.dss.testing.fake.testShopifyConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test

class MonolithShopifyWebhookHandlersTest {

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() =
    runDssApp(handlers(internalSecret = "y".repeat(32))) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("unauthorized" in r.bodyAsText())
    }

  @Test
  fun `sync-shipments accepts when internal secret matches`() =
    runDssApp(handlers(internalSecret = "y".repeat(32), sandboxFakeShopify = true)) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        header("X-DSS-Internal-Secret", "y".repeat(32))
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
    }

  // ---------- validation ----------

  @Test
  fun `sync-shipments returns 400 for non-positive shopify_order_id`() = runDssApp(handlers()) { client ->
    val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifyOrderId = 0))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_order_id" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = runDssApp(handlers()) { client ->
    val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for malformed JSON body`() = runDssApp(handlers()) { client ->
    val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("invalid request body" in r.bodyAsText())
  }

  // ---------- sandbox short-circuit ----------

  @Test
  fun `sync-shipments returns stub response when sandboxFakeShopify is true`() =
    runDssApp(handlers(sandboxFakeShopify = true)) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(resp.newFulfillmentIds.single() == 9_000_000_000_000_001L)
    }

  @Test
  fun `tracking-update returns stub response when sandboxFakeShopify is true`() =
    runDssApp(handlers(sandboxFakeShopify = true)) { client ->
      val r = client.post(DssPaths.TRACKING_UPDATE) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<TrackingUpdateResponse>()
      assert(resp.fulfillmentEventId == 9_000_000_000_000_001L)
    }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when no token in header or env`() =
    runDssApp(handlers(shopAccessTokens = ConcurrentHashMap())) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.bodyAsText())
    }

  // ---------- handlePutStoreApiKey ----------

  @Test
  fun `PUT stores api-key caches token in shopAccessTokens map`() {
    val tokens = ShopAccessTokenCache()
    runDssApp(handlers(shopTokens = tokens)) { client ->
      val r = client.put(DssPaths.STORES_API_KEY) {
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
  }

  @Test
  fun `PUT stores api-key forwards normalised request to monolith`() {
    val tokens = ShopAccessTokenCache()
    val fake = FakeMonolithService()
    runDssApp(handlers(shopTokens = tokens, monolith = fake)) { client ->
      val r = client.put(DssPaths.STORES_API_KEY) {
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
  }

  @Test
  fun `PUT stores api-key does not call monolith when none is configured`() {
    val tokens = ShopAccessTokenCache()
    runDssApp(handlers(shopTokens = tokens, monolith = null)) { client ->
      val r = client.put(DssPaths.STORES_API_KEY) {
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
  }

  @Test
  fun `PUT stores api-key still caches token when monolith returns an error`() {
    val tokens = ShopAccessTokenCache()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    runDssApp(handlers(shopTokens = tokens, monolith = fake)) { client ->
      val r = client.put(DssPaths.STORES_API_KEY) {
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
  }

  @Test
  fun `PUT stores api-key rejects invalid shopify_subdomain as 400`() = runDssApp(handlers()) { client ->
    val r = client.put(DssPaths.STORES_API_KEY) {
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
  fun `PUT stores api-key requires internal secret when configured`() =
    runDssApp(handlers(internalSecret = "z".repeat(32))) { client ->
      val r = client.put(DssPaths.STORES_API_KEY) {
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

  /**
   * Mounts production [installDssRoutes] + [installJsonContentNegotiation] inside ktor's in-memory
   * test engine and exposes a content-negotiating client to [block]. No sockets, no random ports.
   * Routes, plugin order, and serialization come from shipping code — only the [MonolithWebhookHandlers]
   * instance is swapped per test.
   */
  private fun runDssApp(
      handlers: MonolithWebhookHandlers,
      block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
  ) = testApplication {
    application { dssRoutesOnly(handlers) }
    val client = createClient {
      install(ClientContentNegotiation) { json(AppJson) }
    }
    block(client)
  }

  /** Mounts plugins + [installDssRoutes] only — sister flows (OAuth, webhooks, demo) stay out so tests are fast. */
  private fun Application.dssRoutesOnly(handlers: MonolithWebhookHandlers) {
    installJsonContentNegotiation()
    routing { installDssRoutes(handlers) }
  }

  private fun handlers(
    internalSecret: String? = null,
    sandboxFakeShopify: Boolean = false,
    shopAccessTokens: MutableMap<String, String> = ConcurrentHashMap<String, String>().apply {
      put("acme.myshopify.com", "shpat_env_token")
    },
    monolith: FakeMonolithService? = null,
    shopTokens: ShopAccessTokenCache = ShopAccessTokenCache(shopAccessTokens),
  ): MonolithWebhookHandlers {
    val shopify = testShopifyConfig()
    val dssConfig = testDssAppConfig(
      shopify = shopify,
      dssInternalSecret = internalSecret,
      sandboxFakeShopify = sandboxFakeShopify,
    )
    // The cache is never queried in these tests (sandbox short-circuits, or missing-token returns 401
    // before any GraphQL call), so reusing the production shared client keeps us off the ad-hoc
    // HttpClient() construction that ArchitectureTest forbids in src/.
    return MonolithWebhookHandlers(
      shopifyApiVersion = shopify.apiVersion,
      dssConfig = dssConfig,
      gqlClientCache = GraphqlClientCache(createSharedHttpClient()),
      fulfillmentService = DssFulfillmentService,
      monolithService = monolith,
      shopTokenCache = shopTokens,
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
