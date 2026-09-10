package dropnext.dss.workflow

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.testutil.fake.FakeMonolithService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!


/**
 * The token store's fallback, which is how a restarted instance recovers the tokens it held in
 * memory. Every way of not finding a token has to answer `null` rather than raise: the caller is a
 * webhook handler, and an exception there would turn a missing token into a `500` that Shopify
 * retries forever.
 */
class ResolveShopTokenFromMonolithTest {

  @Test
  fun `a store the monolith knows answers its api key`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") }
    assert(resolveShopTokenFromMonolith(monolith, acmeShop) == ShopifyAdminToken("shpat_from_monolith"))
  }

  /** The monolith identifies a store by its subdomain; sending the full host would find nothing. */
  @Test
  fun `the lookup asks by subdomain rather than by the myshopify host`() = runBlocking {
    val monolith = FakeMonolithService()
    resolveShopTokenFromMonolith(monolith, acmeShop)
    assert(monolith.getStoreCalls.single() == "acme")
  }

  @Test
  fun `a store the monolith does not know answers null`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    assert(resolveShopTokenFromMonolith(monolith, acmeShop) == null)
  }

  @Test
  fun `a known store with no api key answers null`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = null }
    assert(resolveShopTokenFromMonolith(monolith, acmeShop) == null)
  }

  @Test
  fun `an unreachable monolith answers null rather than raising`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
    assert(resolveShopTokenFromMonolith(monolith, acmeShop) == null)
  }
}
