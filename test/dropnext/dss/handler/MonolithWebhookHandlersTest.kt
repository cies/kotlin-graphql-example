package dropnext.dss.handler

import dropnext.dss.path.Paths
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyRequest
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyResponse
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.plugin.installMonolithWebhookAuth
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installMonolithWebhookRoutes
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.fulfillment.diagramCrossFoOrder
import dropnext.dss.lib.shopify.graphql.fulfillment.diagramCrossFoShipment
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testing.fake.okResponse
import dropnext.dss.workflow.minimalOrder
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
private val defaultInternalSecret = "z".repeat(32)


class MonolithWebhookHandlersTest {

  // ---------- internal-secret gate ----------

  @Test
  fun `sync-shipments returns 401 when internal secret is required and not provided`() {
    val secret = "y".repeat(32)
    runDssApp(handlers(), secret = secret) { client ->
      val unauthenticatedClient = createClient {
        install(ClientContentNegotiation) { json(AppJson) }
      }
      val r = unauthenticatedClient.post(Paths.syncShipmentsWithFulfillments) {
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
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        header("Authorization", "Bearer $secret")
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
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifyOrderId = 0))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_order_id" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for invalid shopify_subdomain`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest().copy(shopifySubdomain = "!!invalid!!"))
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for malformed JSON body`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody("{ this is not json")
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("invalid request body" in r.bodyAsText())
  }

  @Test
  fun `sync-shipments returns 400 for duplicate tracking numbers`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(
        validSyncRequest().copy(
          shipments = listOf(
            validSyncRequest().shipments.single(),
            validSyncRequest().shipments.single(),
          ),
        ),
      )
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("duplicate tracking_number" in r.bodyAsText())
  }

  // ---------- sync-shipments success + dry-run ----------

  @Test
  fun `sync-shipments returns 200 with new_fulfillment_ids`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    fakeShopify.createFulfillmentWithLineItemsResponse = okResponse(
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(
            id = "gid://shopify/Fulfillment/5001",
            legacyResourceId = "5001",
          ),
          userErrors = emptyList(),
        ),
      ),
    )
    val tokens = ShopAccessTokenCache().apply { this[acmeShop] = "shpat_test" }
    runDssApp(
      handlers(
        shopTokens = tokens,
        shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify),
      ),
    ) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(validSyncRequest())
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(body.newFulfillmentIds == listOf(5001L))
      assert("new_fulfillment_ids" in r.bodyAsText())
    }
  }

  @Test
  fun `sync-shipments returns 400 on dry-run quantity failure before cancel`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    val tokens = ShopAccessTokenCache().apply { this[acmeShop] = "shpat_test" }
    runDssApp(
      handlers(
        shopTokens = tokens,
        shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify),
      ),
    ) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(
              validSyncRequest().shipments.single().copy(
                lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 99)),
              ),
            ),
          ),
        )
      }
      assert(r.status == HttpStatusCode.BadRequest)
      assert("exceeds remaining" in r.bodyAsText())
      assert(fakeShopify.cancelFulfillmentCalls.isEmpty())
      assert(fakeShopify.createFulfillmentWithLineItemsCalls.isEmpty())
    }
  }

  @Test
  fun `sync-shipments returns 200 for partial match with unmatched variant skipped`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    fakeShopify.createFulfillmentWithLineItemsResponse = okResponse(
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(
            id = "gid://shopify/Fulfillment/5002",
            legacyResourceId = "5002",
          ),
          userErrors = emptyList(),
        ),
      ),
    )
    val tokens = ShopAccessTokenCache().apply { this[acmeShop] = "shpat_test" }
    runDssApp(
      handlers(
        shopTokens = tokens,
        shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify),
      ),
    ) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(
              Shipment(
                trackingNumber = "TRK-MIXED",
                carrier = "UPS",
                trackingUrl = null,
                lineItems = listOf(
                  ShipmentLineItem(productVariantId = 101L, quantity = 1),
                  ShipmentLineItem(productVariantId = 999L, quantity = 1),
                ),
              ),
            ),
          ),
        )
      }
      assert(r.status == HttpStatusCode.OK)
      val body = r.body<SyncShipmentsWithFulfillmentsResponse>()
      assert(body.newFulfillmentIds == listOf(5002L))
      assert(fakeShopify.createFulfillmentWithLineItemsCalls.size == 1)
    }
  }

  @Test
  fun `sync-shipments cross-FO shipment sends two fulfillment order groups to create`() {
    val fakeShopify = FakeShopifyGraphqlService()
    fakeShopify.loadOrderForDssResponse =
      okResponse(GetOrderForDss.Result(order = diagramCrossFoOrder()))
    fakeShopify.createFulfillmentWithLineItemsResponse = okResponse(
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(
            id = "gid://shopify/Fulfillment/5100",
            legacyResourceId = "5100",
          ),
          userErrors = emptyList(),
        ),
      ),
    )
    val tokens = ShopAccessTokenCache().apply { this[acmeShop] = "shpat_test" }
    runDssApp(
      handlers(
        shopTokens = tokens,
        shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = fakeShopify),
      ),
    ) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody(
          validSyncRequest().copy(
            shipments = listOf(diagramCrossFoShipment()),
          ),
        )
      }
      assert(r.status == HttpStatusCode.OK)
      val createCall = fakeShopify.createFulfillmentWithLineItemsCalls.single()
      assert(createCall.lineItemsByFulfillmentOrder.size == 2)
      val foIds = createCall.lineItemsByFulfillmentOrder.map { it.fulfillmentOrderId }
      assert("gid://shopify/FulfillmentOrder/301" in foIds)
      assert("gid://shopify/FulfillmentOrder/302" in foIds)
      assert(createCall.tracking.number == "TRK-A")
    }
  }

  // ---------- missing token ----------

  @Test
  fun `sync-shipments returns 401 when shop has no Admin token`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody(validSyncRequest())
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.bodyAsText())
  }

  @Test
  fun `tracking-update returns 401 when shop has no Admin token`() = runDssApp(handlers()) { client ->
    val r = client.post(Paths.trackingUpdate) {
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
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(
          UpdateStoreApiKeyRequest(
            shopifySubdomain = "acme",
            apiKey = "shpat_new_token",
            shopifyShopId = 99L,
          ),
        )
      }
      assert(r.status == HttpStatusCode.OK)
      val resp = r.body<UpdateStoreApiKeyResponse>()
      assert(resp.storeId == 1L) // FakeMonolithService.putStoreApiKeyStoreId default
      assert(tokens[acmeShop] == "shpat_new_token")
    }
  }

  @Test
  fun `PUT stores api-key forwards normalised request to monolith`() {
    val tokens = ShopAccessTokenCache()
    val fake = FakeMonolithService()
    runDssApp(handlers(shopTokens = tokens, monolith = fake)) { client ->
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(
          UpdateStoreApiKeyRequest(
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
      val r = client.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(
          UpdateStoreApiKeyRequest(
            shopifySubdomain = "acme",
            apiKey = "shpat_x",
            shopifyShopId = 0L,
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
    val r = client.put(Paths.storesApiKey) {
      contentType(ContentType.Application.Json)
      setBody(
        UpdateStoreApiKeyRequest(
          shopifySubdomain = "!!invalid!!",
          apiKey = "shpat",
          shopifyShopId = 0L,
        ),
      )
    }
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shopify_subdomain" in r.bodyAsText())
  }

  @Test
  fun `PUT stores api-key requires internal secret when configured`() =
    runDssApp(handlers(), secret = "z".repeat(32)) { client ->
      val unauthenticatedClient = createClient {
        install(ClientContentNegotiation) { json(AppJson) }
      }
      val r = unauthenticatedClient.put(Paths.storesApiKey) {
        contentType(ContentType.Application.Json)
        setBody(
          UpdateStoreApiKeyRequest(
            shopifySubdomain = "acme",
            apiKey = "shpat_x",
            shopifyShopId = 0L,
          ),
        )
      }
      assert(r.status == HttpStatusCode.Unauthorized)
    }

  // ---------- helpers ----------

  /**
   * Mounts production routing + auth + content-negotiation inside Ktor's in-memory test engine.
   * Every DSS-internal route requires `Authorization: Bearer <secret>` to match.
   */
  private fun runDssApp(
    handlers: MonolithWebhookHandlers,
    secret: String = defaultInternalSecret,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
  ) = testApplication {
    application { dssRoutesOnly(handlers, secret) }
    val client = createClient {
      install(ClientContentNegotiation) { json(AppJson) }
      defaultRequest { header("Authorization", "Bearer $secret") }
    }
    block(client)
  }

  private fun Application.dssRoutesOnly(handlers: MonolithWebhookHandlers, secret: String) {
    installJsonContentNegotiation()
    installMonolithWebhookAuth(secret)
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
      shopAccessTokenCache = shopTokens,
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
