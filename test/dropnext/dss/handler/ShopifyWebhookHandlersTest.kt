package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.dssDependencies
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError

import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.withDssApp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock



private const val WEBHOOK_SECRET = "shpss_test_webhook_secret"


/**
 * The inbound Shopify webhook through the production module: the HMAC gate, the topic routing and
 * the outbound effect. Signing happens here rather than through our own verifier, so a change to
 * `ShopifyHmacVerifierService` cannot make both sides agree on the wrong thing.
 */
class ShopifyWebhookHandlersTest {

  @Test
  fun `rejects request with missing HMAC header as 401`() = withDssApp(deps()) { client ->
    val r = client.post(Paths.webhooksShopify) {
      header("X-Shopify-Topic", "orders/create")
      setBody("""{"id":1}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `bad HMAC on orders_create gates out monolith POST`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.post(Paths.webhooksShopify) {
        header("X-Shopify-Topic", "orders/create")
        header("X-Shopify-Shop-Domain", "acme.myshopify.com")
        header("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(ByteArray(32)))
        setBody("""{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""")
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `accepts valid HMAC for unknown topic and returns 200 without calling monolith`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("shop/update", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `returns 200 without Graphql when shop domain cannot be resolved`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1}""", shopDomain = null)
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `returns 200 without Graphql when no token is available for the shop`() {
    // The default graph resolves no service, so `forShop` answers null.
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith)) { client ->
      val r = client.signedWebhook("products/create", """{"id":1,"domain":"acme.myshopify.com"}""")
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
    }
  }

  @Test
  fun `orders_create end-to-end POSTs the mapped order to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/create",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      val forwarded = monolith.createOrderCalls.single()
      assert(forwarded.shopifyOrderId == 1001L)
      assert(forwarded.shopifySubdomain == "acme")
      assert(forwarded.lineItems.single().fulfillmentOrderId == 301L)
      assert(shopify.orderForDssCalls.single() == "gid://shopify/Order/1001")
    }
  }

  // ---------- product webhook flows ----------

  @Test
  fun `products_create loads the product and upserts mapped variants to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "501", variantId = "9001"), "EUR"))
    }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/create",
        """{"id":501,"admin_graphql_api_id":"gid://shopify/Product/501","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(shopify.productByIdCalls.single() == "gid://shopify/Product/501")
      val upsert = monolith.upsertProductVariantsCalls.single()
      assert(upsert.shopifySubdomain == "acme")
      assert(upsert.productVariants.single().productVariantId == 9001L)
      assert(upsert.productVariants.single().priceCurrency == "EUR")
    }
  }

  @Test
  fun `products_update routes through the same upsert path as products_create`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResult = Success(ShopProduct(sampleProduct(legacyResourceId = "502", variantId = "9002"), "USD"))
    }
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/update",
        """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.upsertProductVariantsCalls.single().productVariants.single().productVariantId == 9002L)
    }
  }

  @Test
  fun `products_create skips monolith call when Shopify returns no product`() {
    val monolith = FakeMonolithService()
    // The fake's default productByIdResult is a successful `null`.
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "products/create",
        """{"id":999,"admin_graphql_api_id":"gid://shopify/Product/999","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(shopify.productByIdCalls.size == 1)
      assert(monolith.upsertProductVariantsCalls.isEmpty())
    }
  }

  @Test
  fun `products_delete deletes variant ids parsed from the webhook body`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.signedWebhook(
        "products/delete",
        """{"id":503,"admin_graphql_api_id":"gid://shopify/Product/503","domain":"acme.myshopify.com","variants":[{"id":701},{"id":702}]}""",
      )
      assert(r.status == HttpStatusCode.OK)
      val deleteReq = monolith.deleteProductVariantsCalls.single()
      assert(deleteReq.shopifySubdomain == "acme")
      assert(deleteReq.productVariantIds == listOf(701L, 702L))
    }
  }

  @Test
  fun `products_delete with no variants in body skips monolith call`() {
    val monolith = FakeMonolithService()
    withDssApp(deps(monolith = monolith, shopify = FakeShopifyGraphqlService())) { client ->
      val r = client.signedWebhook(
        "products/delete",
        """{"id":503,"admin_graphql_api_id":"gid://shopify/Product/503","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.deleteProductVariantsCalls.isEmpty())
    }
  }

  @Test
  fun `orders_updated does not POST to the monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
      val r = client.signedWebhook(
        "orders/updated",
        """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
      )
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCalls.isEmpty())
      // With sync disabled, the handler also skips the Graphql fetch.
      assert(shopify.orderForDssCalls.isEmpty())
    }
  }

  /**
   * After an uninstall every webhook for the shop hits a `401` at Shopify. The delivery is still
   * acknowledged, and the log has to say what happened, because that line is all an operator gets.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `orders_create with a rejected token is acknowledged and logged as a token problem`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.TokenRejected(401)) }
    val lines = capturingLogs {
      withDssApp(deps(monolith = monolith, shopify = shopify)) { client ->
        val r = client.signedWebhook(
          "orders/create",
          """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}""",
        )
        assert(r.status == HttpStatusCode.OK)
      }
    }
    assert(monolith.createOrderCalls.isEmpty())
    val line = lines.single { "could not load order" in it }
    assert("Shopify rejected the Admin token (HTTP 401)" in line)
    assert("network" !in line.lowercase())
  }

  // ---------- helpers ----------

  /** A webhook signed the way Shopify signs one: base64 HMAC-SHA256 over the exact body bytes. */

  private suspend fun HttpClient.signedWebhook(
    topic: String,
    body: String,
    shopDomain: String? = "acme.myshopify.com",
  ): HttpResponse = post(Paths.webhooksShopify) {
    header("X-Shopify-Topic", topic)
    shopDomain?.let { header("X-Shopify-Shop-Domain", it) }
    header("X-Shopify-Hmac-Sha256", base64HmacSha256(WEBHOOK_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
    setBody(body)
  }

  /**
   * Default graph: no Admin token resolvable for any shop, so `forShop` answers null and the handler
   * logs the "no Admin token" warning. Tests that need a working service pass [shopify].
   */
  private fun deps(
    monolith: MonolithService = FakeMonolithService(),
    shopify: FakeShopifyGraphqlService? = null,
  ): DssDependencies = dssDependencies(
    config = testConfig(appClientSecret = WEBHOOK_SECRET),
    monolithService = monolith,
    shopTokens = InMemoryShopTokenStore(),
    shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = shopify),
  )
}
