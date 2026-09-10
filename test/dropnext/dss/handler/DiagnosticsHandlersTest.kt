package dropnext.dss.handler

import dropnext.dss.DssDependencies
import dropnext.dss.config.Config
import dropnext.dss.contract.ErrorResponse
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.dssDependencies
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private val dssApiKey = "d".repeat(32)


/**
 * The diagnostic endpoints. They are unauthenticated and reachable from anywhere the service is,
 * so what they must *not* say matters as much as what they do: `/api` renders a configuration
 * summary, and a field added there carelessly would publish a secret.
 */
class DiagnosticsHandlersTest {

  @Test
  fun `the index lists every route the service serves`() = withDssApp(deps()) { client ->
    val body = client.get(Paths.index).bodyAsText()
    assert(Paths.health in body)
    assert(Paths.webhooksShopify in body)
    assert(Paths.syncShipmentsWithFulfillments in body)
    assert(Paths.trackingUpdate in body)
    assert(Paths.storesApiKey in body)
  }

  @Test
  fun `health answers ok`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.health)
    assert(r.status == HttpStatusCode.OK)
    assert(r.bodyAsText() == "ok")
  }

  @Test
  fun `the api status summary reports the bind address and the base url`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.api)
    assert(r.status == HttpStatusCode.OK)
    val body = r.bodyAsText()
    assert("\"status\":\"ok\"" in body)
    assert("0.0.0.0:8080" in body)
    assert("https://dss.test" in body)
  }

  /**
   * The one assertion that has to be about absence: every secret the configuration holds, checked
   * against the body of the endpoint most likely to grow a field that leaks one.
   */
  @Test
  fun `the api status summary carries no secret`() {
    val config = testConfig(
      appClientSecret = "shpss_app_secret_value",
      dssApiKey = dssApiKey,
      monolithApiKey = "mono_api_key_value",
      shopAccessTokens = mapOf(acmeShop to ShopifyAdminToken("shpat_seeded_token")),
    )
    withDssApp(deps(config = config)) { client ->
      val body = client.get(Paths.api).bodyAsText()
      assert("shpss_app_secret_value" !in body)
      assert("mono_api_key_value" !in body)
      assert("shpat_seeded_token" !in body)
      assert(dssApiKey !in body)
    }
  }

  @Test
  fun `api check without a shop parameter is a 400`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.apiCheck)
    assert(r.status == HttpStatusCode.BadRequest)
    assert(r.body<ErrorResponse>().error == "Missing shop")
  }

  @Test
  fun `api check with a malformed shop is a 400`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.apiCheck}?shop=!!invalid!!")
    assert(r.status == HttpStatusCode.BadRequest)
    assert("shop" in r.body<ErrorResponse>().error)
  }

  @Test
  fun `api check answers 401 for a shop with no resolvable token`() = withDssApp(deps()) { client ->
    val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
    assert(r.status == HttpStatusCode.Unauthorized)
    assert("missing Shopify Admin token" in r.body<ErrorResponse>().error)
  }

  @Test
  fun `api check answers 200 for a shop whose token resolves`() {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test")))
    withDssApp(deps(tokens = tokens)) { client ->
      val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      assert(r.status == HttpStatusCode.OK)
      val body = r.bodyAsText()
      assert("\"shop\":\"acme.myshopify.com\"" in body)
      assert("\"hasTokenMappedForShop\":true" in body)
      // The check reports that a token exists; it never reports the token.
      assert("shpat_test" !in body)
    }
  }

  @Test
  fun `the redirect url endpoint answers the url Shopify is configured to call back`() =
    withDssApp(deps()) { client ->
      val r = client.get(Paths.apiRedirectUrl)
      assert(r.status == HttpStatusCode.OK)
      assert(r.bodyAsText() == "https://dss.test/oauth/callback")
    }

  // ---------- helpers ----------

  private fun deps(
    config: Config = testConfig(dssApiKey = dssApiKey),
    tokens: InMemoryShopTokenStore = InMemoryShopTokenStore(),
  ): DssDependencies = dssDependencies(
    config = config,
    monolithService = FakeMonolithService(),
    shopTokens = tokens,
  )
}
