package dropnext.dss.handler

import dropnext.dss.dssDependencies
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.path.Paths
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testing.fake.okResponse
import dropnext.dss.testing.fake.testConfig
import dropnext.dss.workflow.minimalOrder
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.ProductStatus
import dropnext.graphql.generated.getproductbyid.MediaConnection
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant
import dropnext.graphql.generated.getproductbyid.ProductVariantConnection
import dropnext.graphql.generated.getproductbyid.ProductVariantEdge
import dropnext.graphql.generated.getproductbyid.Shop as GetProductByIdShop
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyWebhookHandlersTest {

  private val secret = "shpss_test_webhook_secret"

  @Volatile
  private var currentHandlers: ShopifyWebhookHandlers? = null

  private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
  private lateinit var baseUrl: String
  private lateinit var httpClient: HttpClient

  @BeforeAll
  fun startServer() {
    server = embeddedServer(CIO, port = 0) {
      routing {
        post(Paths.webhooksShopify) {
          val h = currentHandlers
          if (h == null) call.respondText("no handlers set", status = HttpStatusCode.InternalServerError)
          else h.handleShopifyWebhook(call)
        }
      }
    }
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    baseUrl = "http://localhost:$port"
    httpClient = HttpClient(OkHttp) {
      engine { config { connectTimeout(2, TimeUnit.SECONDS); readTimeout(5, TimeUnit.SECONDS) } }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
    }
  }

  @AfterAll
  fun stopServer() {
    httpClient.close()
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  @AfterTest
  fun clearHandlers() {
    currentHandlers = null
  }

  // ---------- tests ----------

  @Test
  fun `rejects request with missing HMAC header as 401`() = runBlocking {
    currentHandlers = handlers()
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "orders/create")
      setBody("""{"id":1}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `bad HMAC on orders_create gates out monolith POST`() = runBlocking {
    val fake = FakeMonolithService()
    currentHandlers = handlers(monolith = fake, shopify = FakeShopifyGraphqlService())
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "orders/create")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(ByteArray(32)))
      setBody("""{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
    assert(fake.createOrderCallCount == 0)
  }

  @Test
  fun `accepts valid HMAC for unknown topic and returns 200 without calling monolith`() = runBlocking {
    val fake = FakeMonolithService()
    currentHandlers = handlers(monolith = fake)
    val body = """{"id":1,"domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "shop/update")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(fake.createOrderCallCount == 0)
  }

  @Test
  fun `returns 200 without Graphql when shop domain cannot be resolved`() = runBlocking {
    val fake = FakeMonolithService()
    currentHandlers = handlers(monolith = fake)
    val body = """{"id":1}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/create")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(fake.createOrderCallCount == 0)
  }

  @Test
  fun `returns 200 without Graphql when no token is available for the shop`() = runBlocking {
    // Default handlers() has shopify=null → FakeShopifyServiceFactory returns null for forShop.
    val fake = FakeMonolithService()
    currentHandlers = handlers(monolith = fake)
    val body = """{"id":1,"domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/create")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(fake.createOrderCallCount == 0)
  }

  @Test
  fun `orders_create end-to-end POSTs the mapped order to the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    }
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "orders/create")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(monolith.createOrderCallCount == 1)
    val forwarded = monolith.lastCreateOrder
    assert(forwarded != null)
    assert(forwarded!!.shopifyOrderId == 1001L)
    assert(forwarded.shopifySubdomain == "acme")
    assert(forwarded.lineItems.single().fulfillmentOrderId == 301L)
    assert(shopify.loadOrderForDssCalls.single() == "gid://shopify/Order/1001")
  }

  // ---------- product webhook flows ----------

  @Test
  fun `products_create loads the product and upserts mapped variants to the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      getProductByIdResponse = okResponse(
        GetProductById.Result(
          product = sampleProduct(legacyResourceId = "501", variantId = "9001"),
          shop = GetProductByIdShop(currencyCode = CurrencyCode.EUR),
        ),
      )
    }
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":501,"admin_graphql_api_id":"gid://shopify/Product/501","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/create")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(shopify.getProductByIdCalls.single() == "gid://shopify/Product/501")
    val upsert = monolith.upsertProductVariantsCalls.single()
    assert(upsert.shopifySubdomain == "acme")
    assert(upsert.productVariants.single().productVariantId == 9001L)
    assert(upsert.productVariants.single().priceCurrency == "EUR")
  }

  @Test
  fun `products_update routes through the same upsert path as products_create`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      getProductByIdResponse = okResponse(
        GetProductById.Result(
          product = sampleProduct(legacyResourceId = "502", variantId = "9002"),
          shop = GetProductByIdShop(currencyCode = CurrencyCode.USD),
        ),
      )
    }
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":502,"admin_graphql_api_id":"gid://shopify/Product/502","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/update")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(monolith.upsertProductVariantsCalls.single().productVariants.single().productVariantId == 9002L)
  }

  @Test
  fun `products_create skips monolith call when Shopify returns no product`() = runBlocking {
    val monolith = FakeMonolithService()
    // FakeShopifyGraphqlService default getProductByIdResponse returns product=null.
    val shopify = FakeShopifyGraphqlService()
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":999,"admin_graphql_api_id":"gid://shopify/Product/999","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/create")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(shopify.getProductByIdCalls.size == 1)
    assert(monolith.upsertProductVariantsCalls.isEmpty())
  }

  @Test
  fun `products_delete deletes variant ids parsed from the webhook body`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":503,"admin_graphql_api_id":"gid://shopify/Product/503","domain":"acme.myshopify.com","variants":[{"id":701},{"id":702}]}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/delete")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    val deleteReq = monolith.deleteProductVariantsCalls.single()
    assert(deleteReq.shopifySubdomain == "acme")
    assert(deleteReq.productVariantIds == listOf(701L, 702L))
  }

  @Test
  fun `products_delete with no variants in body skips monolith call`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":503,"admin_graphql_api_id":"gid://shopify/Product/503","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "products/delete")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(monolith.deleteProductVariantsCalls.isEmpty())
  }

  @Test
  fun `orders_updated does not POST to the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    currentHandlers = handlers(monolith = monolith, shopify = shopify)
    val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${Paths.webhooksShopify}") {
      header("X-Shopify-Topic", "orders/updated")
      header("X-Shopify-Shop-Domain", "acme.myshopify.com")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(monolith.createOrderCallCount == 0)
    // With sync disabled, the handler also skips the Graphql fetch.
    assert(shopify.loadOrderForDssCalls.isEmpty())
  }

  // ---------- factory helpers ----------

  /**
   * Default handler wiring. By default the shop has no token resolvable
   * (`FakeShopifyServiceFactory(service = null)`), so `forShop` returns `null` and the handler
   * logs the "no Admin token" warning. Tests that need a working service pass [shopify].
   */
  private fun handlers(
    monolith: MonolithService = FakeMonolithService(),
    shopify: FakeShopifyGraphqlService? = null,
  ): ShopifyWebhookHandlers = dssDependencies(
    config = testConfig(
      appClientSecret = secret,
    ),
    httpClient = httpClient,
    monolithService = monolith,
    shopTokens = ShopAccessTokenCache(),
    shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = shopify),
  ).shopifyWebhookHandlers

  private fun base64HmacSha256(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(body))
  }

  /** Minimal `Product` for the GetProductById stub — just enough to map to a single [ProductVariantItem]. */
  private fun sampleProduct(legacyResourceId: String, variantId: String): Product = Product(
    id = "gid://shopify/Product/$legacyResourceId",
    legacyResourceId = legacyResourceId,
    title = "Sample",
    description = "",
    descriptionHtml = "",
    vendor = "",
    productType = "",
    tags = emptyList(),
    handle = "sample",
    status = ProductStatus.ACTIVE,
    publishedAt = "2026-04-01T00:00:00Z",
    createdAt = "2026-04-01T00:00:00Z",
    updatedAt = "2026-04-01T00:00:00Z",
    media = MediaConnection(edges = emptyList()),
    variants = ProductVariantConnection(
      edges = listOf(
        ProductVariantEdge(
          node = ProductVariant(
            id = "gid://shopify/ProductVariant/$variantId",
            legacyResourceId = variantId,
            title = "Default",
            sku = "SKU-$variantId",
            barcode = null,
            price = "10.00",
            updatedAt = "2026-04-01T00:00:00Z",
            selectedOptions = emptyList(),
            media = MediaConnection(edges = emptyList()),
          ),
        ),
      ),
    ),
  )
}
