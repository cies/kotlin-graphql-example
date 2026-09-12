package dropnext.dss.lib.shopify.graphql

import dev.forkhandles.result4k.Failure
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.workflow.resolveShopTokenFromMonolith
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
/** Deliberately not the default: the factory must use the version it was configured with. */
private const val API_VERSION = "2025-01"


/**
 * The factory decides, once per request, whether a shop can be talked to at all. A webhook handler acknowledges a
 * `Missing` with a `200` and asks Shopify to redeliver an `Unavailable`, so the fallback to the monolith and its
 * failure modes are worth pinning.
 *
 * Requests go through the rewriting client to a fake Shopify, so the URL the factory builds is
 * observable rather than assumed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Stop it from unnecessarily reconstructing per instance.
class HttpShopifyGraphqlServiceFactoryTest {

  private lateinit var shopify: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient

  @BeforeAll
  fun startServer() {
    shopify = FakeShopifyGraphqlServer()
    httpClient = shopifyRewritingHttpClient(shopify.start())
  }

  @AfterAll
  fun stopServer() {
    httpClient.close()
    shopify.stop()
  }

  @Test
  fun `a cached token is used without asking the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val factory = factoryFor(monolith, cached = mapOf(acmeShop to ShopifyAdminToken("shpat_cached")))

    val lookup = factory.forShop(acmeShop)

    assert((lookup as ShopLookup.Found).value.shop == acmeShop)
    assert(monolith.getStoreCalls.isEmpty())
  }

  @Test
  fun `a shop with no cached token is looked up on the monolith by subdomain`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") }
    val tokens = tokenStore(monolith)
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)

    val lookup = factory.forShop(acmeShop)

    assert(lookup is ShopLookup.Found)
    // The monolith knows stores by subdomain, not by the full myshopify host.
    assert(monolith.getStoreCalls.single() == "acme")
    assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_from_monolith"))
  }

  @Test
  fun `a second call for the same shop does not ask the monolith again`() = runBlocking {
    val monolith = FakeMonolithService()
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokenStore(monolith), API_VERSION)

    factory.forShop(acmeShop)
    factory.forShop(acmeShop)

    assert(monolith.getStoreCalls.size == 1)
  }

  @Test
  fun `a shop the monolith does not know has no service`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokenStore(monolith), API_VERSION)

    assert(factory.forShop(acmeShop) == ShopLookup.Missing)
  }

  /** A store the monolith knows but has no token for is as unusable as one it does not know. */
  @Test
  fun `a store without an api key has no service`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = null }
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokenStore(monolith), API_VERSION)

    assert(factory.forShop(acmeShop) == ShopLookup.Missing)
  }

  /** An unreachable monolith is not a shop without a token: the caller has to be able to ask for a retry. */
  @Test
  fun `an unreachable monolith makes the service unavailable rather than missing`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
    val tokens = tokenStore(monolith)
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)

    assert(factory.forShop(acmeShop) == ShopLookup.Unavailable)
    assert(tokens.cached(acmeShop) == null)
  }

  @Test
  fun `the service it hands back talks to the configured API version with the shop's token`() = runBlocking {
    val factory = factoryFor(FakeMonolithService(), cached = mapOf(acmeShop to ShopifyAdminToken("shpat_cached")))
    val service = (factory.forShop(acmeShop) as ShopLookup.Found).value

    service.shopIdentity()

    val call = shopify.calls.single()
    assert(call.authorization == "shpat_cached")
    assert(call.path == "/admin/api/$API_VERSION/graphql.json")
    shopify.clear()
  }

  /**
   * A reinstall through another instance leaves this one holding a token Shopify now refuses. The
   * `401` must evict it, so the next request re-resolves from the monolith instead of failing until a restart.
   */
  @Test
  fun `a 401 from Shopify evicts the token and the next call asks the monolith again`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_after_reinstall") }
    val factory = factoryFor(monolith, cached = mapOf(acmeShop to ShopifyAdminToken("shpat_revoked")))
    shopify.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)

    val rejected = (factory.forShop(acmeShop) as ShopLookup.Found).value.shopIdentity()
    assert((rejected as Failure).reason == ShopifyError.TokenRejected(401))

    factory.forShop(acmeShop)
    assert(monolith.getStoreCalls.single() == "acme")
    shopify.clear()
  }

  /** The `401` of a request that started out with the old token must not throw away the token a reinstall remembered meanwhile. */
  @Test
  fun `a 401 for an old token leaves a newer token in place`() = runBlocking {
    val tokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_revoked")))
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)
    val serviceWithOldToken = (factory.forShop(acmeShop) as ShopLookup.Found).value
    tokens.remember(acmeShop, ShopifyAdminToken("shpat_after_reinstall"))
    shopify.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)

    serviceWithOldToken.shopIdentity()

    assert(tokens.cached(acmeShop) == ShopifyAdminToken("shpat_after_reinstall"))
    shopify.clear()
  }

  // ---------- helpers ----------

  private fun tokenStore(monolith: FakeMonolithService): InMemoryShopTokenStore =
    InMemoryShopTokenStore { shop -> resolveShopTokenFromMonolith(monolith, shop) }

  private fun factoryFor(
    monolith: FakeMonolithService,
    cached: Map<ShopDomain, ShopifyAdminToken>,
  ): HttpShopifyGraphqlServiceFactory {
    val tokens = InMemoryShopTokenStore(cached) { shop -> resolveShopTokenFromMonolith(monolith, shop) }
    return HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)
  }
}
