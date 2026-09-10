package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.DssDependencies
import dropnext.dss.config.Config
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.dssDependencies
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.hexHmacSha256
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private const val OAUTH_SECRET = "oauth-test-secret"
private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!


/**
 * The install and callback routes through the production module. The callback's happy path is the
 * one place the OAuth code exchange runs over real HTTP, because `ShopifyOAuthService` speaks to
 * Shopify directly rather than through `ShopifyGraphqlService`.
 */
class OAuthHandlersTest {

  @Test
  fun `install returns 400 when shop query param is missing`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.install)
    assert(r.status == HttpStatusCode.BadRequest)
    // A merchant's browser reads this, so it is plain text and not the JSON error of the API routes.
    assert(r.contentType()?.withoutParameters() == ContentType.Text.Plain)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `install returns 400 when shop is malformed`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.install}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `install redirects to Shopify authorize url for valid shop`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.install}?shop=acme.myshopify.com")
    assert(r.status == HttpStatusCode.Found)
    val location = r.headers["Location"]
    assert(location != null && location.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-test" in location!!)
  }

  @Test
  fun `oauth callback returns 400 when hmac is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing hmac" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when shop is invalid`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=!bad!&code=c&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Invalid shop" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when state is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&code=c")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing state" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 400 when code is missing`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=x&shop=acme.myshopify.com&state=s")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("Missing code" in r.bodyAsText())
  }

  @Test
  fun `oauth callback returns 403 when hmac is wrong`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.defaultOAuthCallback}?hmac=0000&shop=acme.myshopify.com&code=c&state=s")
    assert(r.status == HttpStatusCode.Forbidden)
    assert("Invalid HMAC" in r.bodyAsText())
  }

  @Test
  fun `oauth callback happy path caches token and persists to monolith`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = acmeShop))
    }
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val deps = deps(config, rewritingClient, monolith, tokens, shopify)
      withDssApp(deps) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config)))
        assert(r.status == HttpStatusCode.OK)
        assert("App installed" in r.bodyAsText())
        assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_fake_admin_token"))
        val forwarded = monolith.putStoreApiKeyCalls.single()
        assert(forwarded.shopifySubdomain == "acme")
        assert(forwarded.shopifyShopId == 9988L)
        assert(forwarded.apiKey == "shpat_fake_admin_token")
        assert("\"code\":\"abc-code\"" in oauthServer.oauthCalls.single())
        assert(shopify.shopIdentityCalls.size == 1)
        // Each known topic was registered once via the workflow.
        assert(shopify.registerWebhookCalls.size == ShopifyWebhookTopic.known.size)
      }
    }
  }

  /**
   * The install report the page renders when the monolith refuses the token: the shop is installed
   * on Shopify's side either way, so the page must say what failed rather than answer an error.
   */
  @Test
  fun `oauth callback reports a failed monolith persist on the install page`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = acmeShop))
    }
    withShopifyOAuthServer { _, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      withDssApp(deps(config, rewritingClient, monolith, tokens, shopify)) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config)))
        assert(r.status == HttpStatusCode.OK)
        val page = r.bodyAsText()
        assert("Saving the token to the monolith failed" in page)
        assert("HTTP status 500" in page)
        // The token is still cached locally: the shop works even while the monolith does not know it.
        assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_fake_admin_token"))
      }
    }
  }

  /** A shop whose identity lookup fails still installs; the report says the shop id is unknown. */
  @Test
  fun `oauth callback survives a failing shop identity lookup`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Failure(ShopifyError.Network("shop identity unreachable"))
    }
    withShopifyOAuthServer { _, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      withDssApp(deps(config, rewritingClient, monolith, tokens, shopify)) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config)))
        assert(r.status == HttpStatusCode.OK)
        assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_fake_admin_token"))
        assert(monolith.putStoreApiKeyCalls.single().shopifyShopId == 0L)
      }
    }
  }

  // ---------- the signed state: what stands between the callback and a CSRF install ----------

  @Test
  fun `oauth callback returns 403 when the state was signed for another shop`() {
    val monolith = FakeMonolithService()
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val foreignState = freshState(rewritingClient, config, shop = ShopDomain.parse("other.myshopify.com")!!)
      withDssApp(deps(config, rewritingClient, monolith)) { client ->
        val r = client.get(signedCallbackUrl(foreignState))
        assert(r.status == HttpStatusCode.Forbidden)
        assert(r.bodyAsText() == "Invalid or expired state")
        assert(oauthServer.oauthCalls.isEmpty())
        assert(monolith.putStoreApiKeyCalls.isEmpty())
      }
    }
  }

  @Test
  fun `oauth callback returns 403 when the state has expired`() {
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val staleState = freshState(rewritingClient, config, now = Instant.now().minusSeconds(600))
      withDssApp(deps(config, rewritingClient)) { client ->
        val r = client.get(signedCallbackUrl(staleState))
        assert(r.status == HttpStatusCode.Forbidden)
        assert(r.bodyAsText() == "Invalid or expired state")
        assert(oauthServer.oauthCalls.isEmpty())
      }
    }
  }

  /** The HMAC covers the query, so a tampered state needs a matching HMAC to get this far; the state's own signature is the last line. */
  @Test
  fun `oauth callback returns 403 when the state was tampered with`() {
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val parts = freshState(rewritingClient, config).split('|')
      val tampered = listOf(parts[0], (parts[1].toLong() + 3600).toString(), parts[2], parts[3]).joinToString("|")
      withDssApp(deps(config, rewritingClient)) { client ->
        val r = client.get(signedCallbackUrl(tampered))
        assert(r.status == HttpStatusCode.Forbidden)
        assert(oauthServer.oauthCalls.isEmpty())
      }
    }
  }

  // ---------- after the checks: the exchange and the service ----------

  @Test
  fun `oauth callback returns 502 and caches nothing when the code exchange fails`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      oauthServer.oauthStatus = HttpStatusCode.BadRequest
      oauthServer.oauthAccessTokenResponse =
        """{"error":"invalid_request","error_description":"The authorization code was not found or was already used"}"""
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      withDssApp(deps(config, rewritingClient, monolith, tokens)) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config), code = "used-code"))
        assert(r.status == HttpStatusCode.BadGateway)
        assert(r.bodyAsText() == "OAuth failed: could not exchange authorization code")
        assert(oauthServer.oauthCalls.size == 1)
        assert(tokens.cached(acmeShop) == null)
        assert(monolith.putStoreApiKeyCalls.isEmpty())
      }
    }
  }

  /** The default graph resolves no service for any shop, which after a successful exchange is the one thing left to fail. */
  @Test
  fun `oauth callback returns 502 when no Shopify service can be built for the shop`() {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    withShopifyOAuthServer { _, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      withDssApp(deps(config, rewritingClient, monolith, tokens)) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config)))
        assert(r.status == HttpStatusCode.BadGateway)
        assert("could not build a Shopify service" in r.bodyAsText())
        // The exchange did succeed, so the token is kept: a retry of the callback would not get a second one.
        assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_fake_admin_token"))
        assert(monolith.putStoreApiKeyCalls.isEmpty())
      }
    }
  }

  /** Shopify may redirect for one host while the shop's canonical `myshopify.com` host is another; a webhook arrives under the latter. */
  @Test
  fun `oauth callback remembers the token under the callback shop and under the canonical domain`() {
    val canonical = ShopDomain.parse("acme-canonical.myshopify.com")!!
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = canonical))
    }
    withShopifyOAuthServer { _, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      withDssApp(deps(config, rewritingClient, monolith, tokens, shopify)) { client ->
        val r = client.get(signedCallbackUrl(freshState(rewritingClient, config)))
        assert(r.status == HttpStatusCode.OK)
        assert("Shop: acme-canonical.myshopify.com" in r.bodyAsText())
        assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_fake_admin_token"))
        assert(tokens.cached(canonical) == ShopifyAdminToken("shpat_fake_admin_token"))
        assert(monolith.putStoreApiKeyCalls.single().shopifySubdomain == "acme-canonical")
      }
    }
  }

  // ---------- what the callback must not log ----------

  /** The callback handles a code, an HMAC, a state and a fresh token in one request: the path most worth checking for a leak. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the oauth callback logs neither the code, the hmac, the state nor the token`() {
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = acmeShop))
    }
    withShopifyOAuthServer { _, rewritingClient ->
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val state = freshState(rewritingClient, config)
      val code = "code-that-must-not-be-logged"
      val lines = capturingLogs {
        withDssApp(deps(config, rewritingClient, FakeMonolithService(), InMemoryShopTokenStore(), shopify)) { client ->
          assert(client.get(signedCallbackUrl(state, code)).status == HttpStatusCode.OK)
        }
      }
      val logged = lines.joinToString("\n")
      assert(lines.isNotEmpty())
      assert(code !in logged)
      assert(callbackHmac(code, state) !in logged)
      assert(state.substringAfterLast('|') !in logged)
      assert("shpat_fake_admin_token" !in logged)
      assert(OAUTH_SECRET !in logged)
    }
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a failed code exchange logs neither the code nor the app secret`() {
    withShopifyOAuthServer { oauthServer, rewritingClient ->
      oauthServer.oauthStatus = HttpStatusCode.BadRequest
      oauthServer.oauthAccessTokenResponse = """{"error":"invalid_request","error_description":"code was already used"}"""
      val config = testConfig(appClientSecret = OAUTH_SECRET)
      val code = "code-that-must-not-be-logged"
      val lines = capturingLogs {
        withDssApp(deps(config, rewritingClient)) { client ->
          assert(client.get(signedCallbackUrl(freshState(rewritingClient, config), code)).status == HttpStatusCode.BadGateway)
        }
      }
      val logged = lines.joinToString("\n")
      assert(lines.any { "OAuth code exchange failed" in it })
      assert(code !in logged)
      assert(OAUTH_SECRET !in logged)
    }
  }

  // ---------- helpers ----------

  /**
   * Runs [block] against a fake Shopify that serves the OAuth code exchange, with a client that

   * rewrites `*.myshopify.com` to it so the production code keeps its real URLs.
   */
  private fun withShopifyOAuthServer(block: (FakeShopifyGraphqlServer, HttpClient) -> Unit) {
    val oauthServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(oauthServer.start())
    try {
      block(oauthServer, rewritingClient)
    } finally {
      rewritingClient.close()
      oauthServer.stop()
    }
  }

  /** The `state` the install redirect would have minted, signed with the secret the handler verifies against. */
  private fun freshState(client: HttpClient, config: Config, shop: ShopDomain = acmeShop, now: Instant = Instant.now()): String =
    oauthService(client, config).signedState(shop, now)

  /** Shopify's query-string HMAC: lowercase hex over the parameters minus the hmac itself, sorted by key. */
  private fun callbackHmac(code: String, state: String): String =
    hexHmacSha256(OAUTH_SECRET, "code=$code&shop=${acmeShop.normalizedShopifyHost}&state=$state")

  /** The callback URL Shopify would send for [state] and [code], correctly signed for `acme`. */
  private fun signedCallbackUrl(state: String, code: String = "abc-code"): String {
    val query = Parameters.build {
      append("shop", acmeShop.normalizedShopifyHost)
      append("code", code)
      append("state", state)
      append("hmac", callbackHmac(code, state))
    }
    return "${Paths.defaultOAuthCallback}?${query.formUrlEncode()}"
  }


  private fun deps(
    config: Config = testConfig(appClientSecret = OAUTH_SECRET),
    httpClient: HttpClient? = null,
    monolith: FakeMonolithService = FakeMonolithService(),
    tokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
    shopify: FakeShopifyGraphqlService? = null,
  ): DssDependencies = dssDependencies(
    config = config,
    httpClient = httpClient ?: shopifyRewritingHttpClient(0),
    monolithService = monolith,
    shopTokens = tokens,
    shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = shopify),
  )

  /**
   * The test signs a state with the same secret the handler verifies against, so it builds its own
   * service from the same config — cheaper than exposing the handler's collaborator.
   */
  private fun oauthService(client: HttpClient, config: Config): ShopifyOAuthService =
    ShopifyOAuthService(
      httpClient = client,
      clientId = config.appClientId,
      clientSecret = config.appClientSecret,
      scopes = config.scopes,
      redirectUrl = config.redirectUrl,
    )
}
