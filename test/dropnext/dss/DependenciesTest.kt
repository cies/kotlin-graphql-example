package dropnext.dss

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.testutil.helper.withDssApp
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private const val APP_SECRET = "shpss_app_secret"


/**
 * The defaults of `dssDependencies`, which every other test overrides. What is pinned is the
 * production token store: seeded from `DSS_SHOP_ACCESS_TOKENS`, and asking the monolith once on a
 * miss, which is how a restarted instance gets its tokens back.
 */
class DependenciesTest {

  @Test
  fun `a token seeded from the configuration answers the readiness check without asking the monolith`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
      val deps = dssDependencies(
        config = testConfig(shopAccessTokens = mapOf(acmeShop to ShopifyAdminToken("shpat_seeded"))),
        httpClient = rewritingClient,
        monolithService = monolith,
      )
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
        assert(r.status == HttpStatusCode.OK)
        assert(monolith.getStoreCalls.isEmpty())
      }
    }

  @Test
  fun `a shop the configuration does not know is looked up on the monolith once and then remembered`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") }
      val deps = dssDependencies(config = testConfig(), httpClient = rewritingClient, monolithService = monolith)
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.OK)
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.OK)
        assert(monolith.getStoreCalls == listOf("acme"))
      }
    }

  @Test
  fun `a shop neither the configuration nor the monolith knows has no token`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
      val deps = dssDependencies(config = testConfig(), httpClient = rewritingClient, monolithService = monolith)
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
        assert(r.status == HttpStatusCode.Unauthorized)
        assert(monolith.getStoreCalls == listOf("acme"))
      }
    }

  /**
   * The readiness check scans the shop's webhook subscriptions through the production factory, so
   * the graph under test points at a fake Shopify that answers the scan with nothing subscribed.
   */
  private fun withFakeShopify(block: (HttpClient) -> Unit) {
    val shopifyServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(shopifyServer.start())
    try {
      shopifyServer.stubData(
        "GetWebhookSubscriptions",
        GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList())),
        GetWebhookSubscriptions.Result.serializer(),
      )
      block(rewritingClient)
    } finally {
      // The application closes the client it was given when it stops; the server is ours to stop.
      shopifyServer.stop()
    }
  }

  /**
   * The seeded token has to reach Shopify itself, through the production factory: the readiness
   * check alone would pass with a store that never hands the token out.
   */
  @Test
  fun `the seeded token is what a webhook presents to Shopify`() {
    val shopifyServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(shopifyServer.start())
    try {
      shopifyServer.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
      val deps = dssDependencies(
        config = testConfig(
          appClientSecret = APP_SECRET,
          shopAccessTokens = mapOf(acmeShop to ShopifyAdminToken("shpat_seeded")),
        ),
        httpClient = rewritingClient,
        monolithService = FakeMonolithService().apply { getStoreReturnsNotFound = true },
      )
      withDssApp(deps) { client ->
        val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
        val r = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", acmeShop.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(APP_SECRET, body.toByteArray()))
          setBody(body)
        }
        assert(r.status == HttpStatusCode.OK)
      }
      val call = shopifyServer.calls.single()
      assert(call.operationName == "GetOrderForDss")
      assert(call.authorization == "shpat_seeded")
    } finally {
      // The application closes the client it was given when it stops; the server is ours to stop.
      shopifyServer.stop()
    }
  }
}
