package dropnext.dss.handler

import dropnext.dss.dssDependencies
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.path.Paths
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testing.fake.okResponse
import dropnext.dss.testing.fake.shopifyRewritingHttpClient
import dropnext.dss.testing.fake.testConfig
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.shopidentity.Shop as ShopIdentityShop
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets
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
        get(Paths.install) {
          val h = current
          if (h == null) call.respondText("no handlers set", status = HttpStatusCode.InternalServerError)
          else h.handleInstall(call)
        }
        get(Paths.defaultOAuthCallback) {
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
    val r = client.get("$baseUrl${Paths.install}")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `install returns 400 when shop is malformed`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.install}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `install redirects to Shopify authorize url for valid shop`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.install}?shop=acme.myshopify.com")
    assert(r.status == HttpStatusCode.Found)
    val location = r.headers["Location"]
    assert(location != null && location.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-test" in location!!)
  }

  @Test
  fun `oauth callback returns 400 when hmac is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.defaultOAuthCallback}?shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing hmac" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.defaultOAuthCallback}?hmac=x&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is invalid`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.defaultOAuthCallback}?hmac=x&shop=!bad!&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when state is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&code=c")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing state" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when code is missing`() = runBlocking {
    current = handlers()
    val r = client.get("$baseUrl${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing code" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 403 when hmac is wrong`() = runBlocking {
    current = handlers()
    val r = client.get(
      "$baseUrl${Paths.defaultOAuthCallback}?hmac=0000&shop=acme.myshopify.com&code=c&state=s",
    )
    assert(r.status == HttpStatusCode.Forbidden)
    assert("Invalid HMAC" in r.bodyAsText())
  }

  @Test
  fun `oauth callback happy path caches token and persists to monolith`() = runBlocking {
    val secret = "oauth-test-secret"
    val shop = "acme.myshopify.com"
    val shopDomain = ShopDomain.parse(shop)!!
    // [FakeShopifyGraphqlServer] is retained only to back the OAuth code-exchange POST
    // (`ShopifyOAuthService` hits real HTTP). The post-OAuth Graphql traffic
    // (`shopIdentity` / `syncProductsPage` / `registerStandardWebhooks`) goes through the
    // in-memory [FakeShopifyGraphqlService] below — no more stubData(...) plumbing.
    val oauthServer = FakeShopifyGraphqlServer()
    val oauthPort = oauthServer.start()
    val rewritingClient = shopifyRewritingHttpClient(oauthPort)
    try {
      val tokens = ShopAccessTokenCache()
      val fakeMonolith = FakeMonolithService()
      val fakeShopify = FakeShopifyGraphqlService(shopDomain).apply {
        shopIdentityResponse = okResponse(
          ShopIdentity.Result(
            shop = ShopIdentityShop(id = "gid://shopify/Shop/9988", myshopifyDomain = shop),
          ),
        )
        // syncProductsPageResponse default (empty page) and registerStandardWebhooksResult
        // default (empty report) are fine here — the assertions don't touch them.
      }
      val dssConfig = testConfig(appClientSecret = secret)
      current = dssDependencies(
        config = dssConfig,
        httpClient = rewritingClient,
        monolithService = fakeMonolith,
        shopTokens = tokens,
        shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(fakeShopify),
      ).oauthHandlers

      // The test signs a state with the same secret the handler will verify against, so it
      // builds its own [ShopifyOAuthService] from the same config (one extra line is cheaper
      // than exposing the handler's internal collaborator).
      val state = ShopifyOAuthService(rewritingClient, dssConfig).signedState(shopDomain)
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
        "$baseUrl${Paths.defaultOAuthCallback}?${Parameters.build {
          appendAll(query)
          append("hmac", hmac)
        }.formUrlEncode()}"
      val r = client.get(callbackUrl)
      assert(r.status == HttpStatusCode.OK)
      assert("App installed" in r.bodyAsText())
      assert(tokens[shopDomain] == "shpat_fake_admin_token")
      assert(fakeMonolith.putStoreApiKeyCallCount == 1)
      val forwarded = fakeMonolith.lastPutStoreApiKey
      assert(forwarded != null)
      assert(forwarded!!.shopifySubdomain == "acme")
      assert(forwarded.shopifyShopId == 9988L)
      assert(forwarded.apiKey == "shpat_fake_admin_token")
      assert(oauthServer.oauthCalls.size == 1)
      assert("\"code\":\"$code\"" in oauthServer.oauthCalls.single())
      assert(fakeShopify.shopIdentityCallCount == 1)
      // Each known topic was registered once via the workflow.
      assert(fakeShopify.registerWebhookCalls.size == ShopifyWebhookTopic.known.size)
    } finally {
      rewritingClient.close()
      oauthServer.stop()
    }
  }

  // ---------- helpers ----------

  private fun handlers(): OAuthHandlers = dssDependencies(
    config = testConfig(appClientSecret = "oauth-test-secret"),
    httpClient = client,
    monolithService = FakeMonolithService(),
  ).oauthHandlers

  private fun hexHmac(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
