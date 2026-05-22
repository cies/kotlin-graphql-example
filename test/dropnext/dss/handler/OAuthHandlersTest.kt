package dropnext.dss.handler

import dropnext.dss.GraphQLClientCache
import dropnext.dss.config.DssPaths
import dropnext.dss.shopify.signedOAuthState
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.dss.testing.fake.shopifyRewritingHttpClient
import dropnext.dss.testing.fake.testDssAppConfig
import dropnext.dss.testing.fake.testShopifyConfig
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.shopidentity.Shop
import dropnext.graphql.generated.syncproductspage.PageInfo
import dropnext.graphql.generated.syncproductspage.ProductConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
class OAuthHandlersTest {

  @Volatile
  private var current: OAuthHandlers? = null

  private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
  private lateinit var baseUrl: String
  private lateinit var client: HttpClient

  @BeforeAll
  fun startServer() {
    server = embeddedServer(CIO, port = 0) {
      routing {
        get(DssPaths.INSTALL) {
          val h = current
          if (h == null) call.respondText("no handlers set", status = HttpStatusCode.InternalServerError)
          else h.handleInstall(call)
        }
        get(DssPaths.DEFAULT_OAUTH_CALLBACK) {
          val h = current
          if (h == null) call.respondText("no handlers set", status = HttpStatusCode.InternalServerError)
          else h.handleOAuthCallback(call)
        }
      }
    }
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    baseUrl = "http://localhost:$port"
    client = HttpClient(OkHttp) {
      followRedirects = false
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
    client.close()
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  @AfterTest
  fun clearHandlers() {
    current = null
  }

  // ---------- tests ----------

  @Test
  fun `install returns 400 when shop query param is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.INSTALL}")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("?shop=" in r.bodyAsText())
  }

  @Test
  fun `install returns 400 when shop is malformed`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.INSTALL}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop domain" in r.bodyAsText())
  }

  @Test
  fun `install redirects to Shopify authorize url for valid shop`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.INSTALL}?shop=acme.myshopify.com")
    assert(r.status == HttpStatusCode.Found)
    val location = r.headers["Location"]
    assert(location != null && location.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-test" in location!!)
  }

  @Test
  fun `oauth callback returns 400 when hmac is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing hmac" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?hmac=x&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is invalid`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?hmac=x&shop=!bad!&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when state is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?hmac=x&shop=acme.myshopify.com&code=c")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing state" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when code is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?hmac=x&shop=acme.myshopify.com&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing code" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 403 when hmac is wrong`() = runBlocking {
    current = handlers()
    val r = client.get(
      "$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?hmac=0000&shop=acme.myshopify.com&code=c&state=s",
    )
    assert(r.status == HttpStatusCode.Forbidden)
    assert("Invalid HMAC" in r.bodyAsText())
  }

  @Test
  fun `oauth callback happy path caches token and persists to monolith`() = runBlocking {
    val secret = "oauth-test-secret"
    val shop = "acme.myshopify.com"
    val shopify = FakeShopifyGraphqlServer()
    val shopifyPort = shopify.start()
    val rewritingClient = shopifyRewritingHttpClient(shopifyPort)
    try {
      shopify.stubData(
        "ShopIdentity",
        ShopIdentity.Result(shop = Shop(id = "gid://shopify/Shop/9988", myshopifyDomain = shop)),
        ShopIdentity.Result.serializer(),
      )
      shopify.stubData(
        "SyncProductsPage",
        SyncProductsPage.Result(
          products = ProductConnection(
            pageInfo = PageInfo(hasNextPage = false, endCursor = null),
            edges = emptyList(),
          ),
        ),
        SyncProductsPage.Result.serializer(),
      )
      shopify.stubData(
        "GetWebhookSubscriptions",
        GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList())),
        GetWebhookSubscriptions.Result.serializer(),
      )
      shopify.stubRaw(
        "RegisterWebhook",
        """{"data":{"webhookSubscriptionCreate":{"userErrors":[],"webhookSubscription":null}}}""",
      )

      val tokens = dropnext.dss.lib.dss.ShopAccessTokenCache()
      val fakeMonolith = FakeMonolithService()
      val shopifyConfig = testShopifyConfig(appClientSecret = secret)
      val dssConfig = testDssAppConfig(shopify = shopifyConfig)
      val cache = GraphQLClientCache(rewritingClient)
      current = OAuthHandlers(dssConfig, rewritingClient, cache, httpMonolithClient = fakeMonolith, shopTokens = tokens)

      val state = signedOAuthState(shop, secret)
      val code = "abc-code"
      val query = Parameters.build {
        append("shop", shop)
        append("code", code)
        append("state", state)
      }
      val canonical = query.entries()
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value.single()}" }
      val hmac = hexHmac(secret, canonical)

      val callbackUrl =
        "$baseUrl${DssPaths.DEFAULT_OAUTH_CALLBACK}?${Parameters.build {
          appendAll(query)
          append("hmac", hmac)
        }.formUrlEncode()}"
      val r = client.get(callbackUrl)
      assert(r.status == HttpStatusCode.OK)
      assert("App installed" in r.bodyAsText())
      assert(tokens[shop] == "shpat_fake_admin_token")
      assert(fakeMonolith.putStoreApiKeyCallCount == 1)
      val forwarded = fakeMonolith.lastPutStoreApiKey
      assert(forwarded != null)
      assert(forwarded!!.shopifySubdomain == "acme")
      assert(forwarded.shopifyShopId == 9988L)
      assert(forwarded.apiKey == "shpat_fake_admin_token")
      assert(shopify.oauthCalls.size == 1)
      assert("\"code\":\"$code\"" in shopify.oauthCalls.single())
    } finally {
      rewritingClient.close()
      shopify.stop()
    }
  }

  // ---------- helpers ----------

  private fun handlers(): OAuthHandlers {
    val shopifyConfig = testShopifyConfig(appClientSecret = "oauth-test-secret")
    val dssConfig = testDssAppConfig(shopify = shopifyConfig)
    val cache = GraphQLClientCache(client)
    return OAuthHandlers(
      dssConfig,
      client,
      cache,
      httpMonolithClient = null,
      shopTokens = dropnext.dss.lib.dss.ShopAccessTokenCache(),
    )
  }

  private fun hexHmac(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
