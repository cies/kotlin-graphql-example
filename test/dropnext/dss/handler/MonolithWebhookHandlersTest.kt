package dropnext.dss.handler

import dropnext.dss.path.Paths
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.plugin.installMonolithWebhookAuthSecret
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installMonolithWebhookRoutes
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServiceFactory
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
import kotlin.test.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!


class MonolithWebhookHandlersTest {

  // ---------- internal-secret gate ----------

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() {
    val secret = "y".repeat(32)
    runDssApp(handlers(), secret = secret) { client ->
      val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("unauthorized" in r.bodyAsText())
    }
  }

  /**
   * Verifies the gate passes when the secret matches: the response is still 401, but with the
   * downstream missing-token reason — proving the request reached the handler. If the gate had
   * failed, the body would say "unauthorized" instead.
   */
  @Test
  fun `sync-shipments reaches handler when internal secret matches`() {
    val secret = "y".repeat(32)
    runDssApp(handlers(), secret = secret) { client ->
      val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        header("X-DSS-Internal-Secret", secret)
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.bodyAsText())
    }
  }

  // ---------- validation ----------

  @Test
  fun `sync-shipments returns 400 for non-positive shopify_order_id`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifyOrderId = 0))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_order_id" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for malformed JSON body`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("invalid request body" in r.bodyAsText())
  }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when shop has no Admin token`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.bodyAsText())
  }

  @Test
  fun `tracking-update returns 401 when shop has no Admin token`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.TRACKING_UPDATE) {
      contentType(ContentType.Application.Json)
      setBody(validTrackingRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.bodyAsText())
  }

  // ---------- handlePutStoreApiKey ----------

  @Test
  fun `PUT stores api-key caches token in shopAccessTokens map`() {
    val tokens = ShopAccessTokenCache()
    runDssApp(handlers(shopTokens = tokens)) { client ->
      val r = client.put(Paths.STORES_API_KEY) {
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
      assert(tokens[acmeShop] == "shpat_new_token")
    }
  }

  @Test
  fun `PUT stores api-key forwards normalised request to monolith`() {
    val tokens = ShopAccessTokenCache()
    val fake = FakeMonolithService()
    runDssApp(handlers(shopTokens = tokens, monolith = fake)) { client ->
      val r = client.put(Paths.STORES_API_KEY) {
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
      assert(tokens[acmeShop] == "shpat_new")
      assert(fake.putStoreApiKeyCallCount == 1)
      val forwarded = fake.lastPutStoreApiKey
      assert(forwarded != null)
      assert(forwarded!!.shopifySubdomain == "acme")
      assert(forwarded.shopifyShopId == 99L)
      assert(forwarded.apiKey == "shpat_new")
    }
  }

  @Test
  fun `PUT stores api-key still caches token when monolith returns an error`() {
    val tokens = ShopAccessTokenCache()
    val fake = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    runDssApp(handlers(shopTokens = tokens, monolith = fake)) { client ->
      val r = client.put(Paths.STORES_API_KEY) {
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
      assert(tokens[acmeShop] == "shpat_x")
      assert(fake.putStoreApiKeyCallCount == 1)
    }
  }

  @Test
  fun `PUT stores api-key rejects invalid shopify_subdomain as 400`() = runDssApp(handlers()) { client ->
    val r = client.put(Paths.STORES_API_KEY) {
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
    runDssApp(handlers(), secret = "z".repeat(32)) { client ->
      val r = client.put(Paths.STORES_API_KEY) {
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
   * Mounts production routing + auth + content-negotiation inside Ktor's in-memory test engine.
   * When [secret] is non-blank, every DSS-internal route requires `X-DSS-Internal-Secret` to match.
   */
  private fun runDssApp(
    handlers: MonolithWebhookHandlers,
    secret: String? = null,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
  ) = testApplication {
    application { dssRoutesOnly(handlers, secret) }
    val client = createClient {
      install(ClientContentNegotiation) { json(AppJson) }
    }
    block(client)
  }

  private fun Application.dssRoutesOnly(handlers: MonolithWebhookHandlers, secret: String?) {
    installJsonContentNegotiation()
    installMonolithWebhookAuthSecret(secret)
    routing { installMonolithWebhookRoutes(handlers) }
  }

  /**
   * Default handlers: an empty token cache and a [FakeShopifyGraphqlServiceFactory] that returns `null`
   * for every shop — `forShop` therefore short-circuits to "missing token" (401). Tests that
   * need a working service pass their own [shopifyGraphqlServiceFactory] (typically wrapping a
   * [dropnext.dss.testing.fake.FakeShopifyGraphqlService]).
   */
  private fun handlers(
    monolith: MonolithService = FakeMonolithService(),
    shopTokens: ShopAccessTokenCache = ShopAccessTokenCache(),
    shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = null),
  ): MonolithWebhookHandlers =
    MonolithWebhookHandlers(
      shopifyGraphqlServiceFactory = shopifyGraphqlServiceFactory,
      monolithService = monolith,
      shopTokenCache = shopTokens,
    )

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
