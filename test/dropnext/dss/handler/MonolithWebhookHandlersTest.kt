package dropnext.dss.handler

import dropnext.dss.path.DssPaths
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentService
import dropnext.dss.lib.shopify.graphql.fulfillment.SandboxFulfillmentService
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.plugin.installDssInternalSecretAuth
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installDssRoutes
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.testing.fake.FakeMonolithService
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


class MonolithShopifyWebhookHandlersTest {

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() =
    runDssApp(handlers(), secret = "y".repeat(32)) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("unauthorized" in r.bodyAsText())
    }

  @Test
  fun `sync-shipments accepts when internal secret matches`() {
    val secret = "y".repeat(32)
    runDssApp(handlers(), secret = secret) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        header("X-DSS-Internal-Secret", secret)
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
    }
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
  fun `sync-shipments returns stub response when sandbox FulfillmentService is wired`() =
    runDssApp(handlers()) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(resp.newFulfillmentIds.single() == SandboxFulfillmentService.SANDBOX_FULFILLMENT_ID)
    }

  @Test
  fun `tracking-update returns stub response when sandbox FulfillmentService is wired`() =
    runDssApp(handlers()) { client ->
      val r = client.post(DssPaths.TRACKING_UPDATE) {
        contentType(ContentType.Application.Json)
        setBody(validTrackingRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<TrackingUpdateResponse>()
      assert(resp.fulfillmentEventId == SandboxFulfillmentService.SANDBOX_FULFILLMENT_ID)
    }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when FulfillmentService reports missing token`() {
    val missingTokenService = object : FulfillmentService {
      override suspend fun syncShipmentsWithFulfillments(
        shop: ShopDomain,
        payload: SyncShipmentsWithFulfillmentsRequest,
      ) = FulfillmentResult.Err.MissingToken

      override suspend fun createTrackingEvent(
        shop: ShopDomain,
        payload: TrackingUpdateRequest,
      ) = FulfillmentResult.Err.MissingToken
    }
    runDssApp(handlers(fulfillmentService = missingTokenService)) { client ->
      val r = client.post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert("missing Shopify Admin token" in r.bodyAsText())
    }
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
      assert(tokens[acmeShop] == "shpat_new_token")
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
      assert(tokens[acmeShop] == "shpat_x")
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
    runDssApp(handlers(), secret = "z".repeat(32)) { client ->
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
    installDssInternalSecretAuth(secret)
    routing { installDssRoutes(handlers) }
  }

  /** Default handlers — [SandboxFulfillmentService] means tests don't need a real Graphql server. */
  private fun handlers(
    monolith: MonolithService = FakeMonolithService(),
    shopTokens: ShopAccessTokenCache = ShopAccessTokenCache(mapOf(acmeShop to "shpat_env_token")),
    fulfillmentService: FulfillmentService = SandboxFulfillmentService(),
  ): MonolithWebhookHandlers =
    MonolithWebhookHandlers(
      fulfillmentService = fulfillmentService,
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
