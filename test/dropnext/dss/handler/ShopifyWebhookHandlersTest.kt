package dropnext.dss.handler

import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.dss.testing.fake.shopifyRewritingHttpClient
import dropnext.dss.testing.fake.testDssAppConfig
import dropnext.dss.testing.fake.testShopifyConfig
import dropnext.dss.testing.fake.testShopifyServiceFactory
import dropnext.dss.workflow.minimalOrder
import dropnext.graphql.generated.GetOrderForDss
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
        post(DssPaths.WEBHOOKS_SHOPIFY) {
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
    val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
      header("X-Shopify-Topic", "orders/create")
      setBody("""{"id":1}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `bad HMAC on orders_create gates out monolith POST`() = runBlocking {
    val fake = FakeMonolithService()
    currentHandlers = handlers(
      monolith = fake,
      tokens = mapOf(ShopDomain.parse("acme.myshopify.com")!! to "shpat_env"),
    )
    val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
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
    val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
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
    val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
      header("X-Shopify-Topic", "products/create")
      header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
      setBody(body)
    }
    assert(r.status == HttpStatusCode.OK)
    assert(fake.createOrderCallCount == 0)
  }

  @Test
  fun `returns 200 without Graphql when no token is available for the shop`() = runBlocking {
    val fake = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    currentHandlers = handlers(monolith = fake, tokens = emptyMap())
    val body = """{"id":1,"domain":"acme.myshopify.com"}"""
    val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
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
    val gqlFake = FakeShopifyGraphqlServer()
    val port = gqlFake.start()
    val rewriter = shopifyRewritingHttpClient(port)
    val monolith = FakeMonolithService()
    currentHandlers = handlersWith(
      monolith = monolith,
      tokens = mapOf(ShopDomain.parse("acme.myshopify.com")!! to "shpat_env"),
      httpClient = rewriter,
    )
    try {
      gqlFake.stubData(
        "GetOrderForDss",
        GetOrderForDss.Result(order = minimalOrder()),
        GetOrderForDss.Result.serializer(),
      )
      val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
      val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
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
      assert(gqlFake.calls.single().operationName == "GetOrderForDss")
      assert(gqlFake.calls.single().authorization == "shpat_env")
    } finally {
      rewriter.close()
      gqlFake.stop()
    }
  }

  @Test
  fun `orders_updated with syncOrderOnUpdated true POSTs to the monolith`() = runBlocking {
    val gqlFake = FakeShopifyGraphqlServer()
    val port = gqlFake.start()
    val rewriter = shopifyRewritingHttpClient(port)
    val monolith = FakeMonolithService()
    currentHandlers = handlersWith(
      monolith = monolith,
      tokens = mapOf(ShopDomain.parse("acme.myshopify.com")!! to "shpat_env"),
      httpClient = rewriter,
      syncOnUpdated = true,
    )
    try {
      gqlFake.stubData(
        "GetOrderForDss",
        GetOrderForDss.Result(order = minimalOrder()),
        GetOrderForDss.Result.serializer(),
      )
      val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
      val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
        header("X-Shopify-Topic", "orders/updated")
        header("X-Shopify-Shop-Domain", "acme.myshopify.com")
        header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
        setBody(body)
      }
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCallCount == 1)
      assert(monolith.lastCreateOrder?.shopifyOrderId == 1001L)
    } finally {
      rewriter.close()
      gqlFake.stop()
    }
  }

  @Test
  fun `orders_updated with syncOrderOnUpdated false does not POST to the monolith`() = runBlocking {
    val gqlFake = FakeShopifyGraphqlServer()
    val port = gqlFake.start()
    val rewriter = shopifyRewritingHttpClient(port)
    val monolith = FakeMonolithService()
    currentHandlers = handlersWith(
      monolith = monolith,
      tokens = mapOf(ShopDomain.parse("acme.myshopify.com")!! to "shpat_env"),
      httpClient = rewriter,
      syncOnUpdated = false,
    )
    try {
      val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
      val r = httpClient.post("$baseUrl${DssPaths.WEBHOOKS_SHOPIFY}") {
        header("X-Shopify-Topic", "orders/updated")
        header("X-Shopify-Shop-Domain", "acme.myshopify.com")
        header("X-Shopify-Hmac-Sha256", base64HmacSha256(secret, body.toByteArray(StandardCharsets.UTF_8)))
        setBody(body)
      }
      assert(r.status == HttpStatusCode.OK)
      assert(monolith.createOrderCallCount == 0)
      // With sync disabled, the handler should also skip the (now removed) bookkeeping Graphql fetch.
      assert(gqlFake.calls.isEmpty())
    } finally {
      rewriter.close()
      gqlFake.stop()
    }
  }

  // ---------- factory helpers ----------

  private fun handlers(
    monolith: MonolithService = FakeMonolithService(),
    tokens: Map<ShopDomain, String> = emptyMap(),
    syncOnUpdated: Boolean = false,
  ): ShopifyWebhookHandlers = handlersWith(monolith, tokens, httpClient, syncOnUpdated)

  private fun handlersWith(
    monolith: MonolithService,
    tokens: Map<ShopDomain, String>,
    httpClient: HttpClient,
    syncOnUpdated: Boolean = false,
  ): ShopifyWebhookHandlers {
    val dssConfig = testDssAppConfig(
      shopify = testShopifyConfig(appClientSecret = secret),
      syncOrderOnUpdated = syncOnUpdated,
    )
    val shopTokens = ShopAccessTokenCache(tokens)
    val factory = testShopifyServiceFactory(
      httpClient = httpClient,
      monolith = monolith,
      tokens = shopTokens,
      apiVersion = dssConfig.shopify.apiVersion,
    )
    return ShopifyWebhookHandlers(dssConfig, factory, monolith)
  }

  private fun base64HmacSha256(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(body))
  }
}
