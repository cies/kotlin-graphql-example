package dropnext.dss.lib.shopify.token

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import kotlin.test.Test
import kotlinx.coroutines.runBlocking


private val acme = ShopDomain.parse("acme.myshopify.com")!!
private val other = ShopDomain.parse("other.myshopify.com")!!

class InMemoryShopTokenStoreTest {

  @Test
  fun `a seeded token resolves without the fallback`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_seed"))) { fallbackCalls++; null }
    assert(store.resolve(acme) == ShopifyAdminToken("shpat_seed"))
    assert(fallbackCalls == 0)
  }

  @Test
  fun `a miss asks the fallback once and remembers its answer`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore { fallbackCalls++; ShopifyAdminToken("shpat_from_monolith") }
    assert(store.resolve(acme) == ShopifyAdminToken("shpat_from_monolith"))
    assert(store.resolve(acme) == ShopifyAdminToken("shpat_from_monolith"))
    assert(fallbackCalls == 1)
    assert(store.cached(acme) == ShopifyAdminToken("shpat_from_monolith"))
  }

  @Test
  fun `a fallback that knows nothing leaves the store empty`() = runBlocking {
    val store = InMemoryShopTokenStore()
    assert(store.resolve(other) == null)
    assert(store.cached(other) == null)
  }

  @Test
  fun `remember overrides what was seeded`() = runBlocking {
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_old")))
    store.remember(acme, ShopifyAdminToken("shpat_new"))
    assert(store.resolve(acme) == ShopifyAdminToken("shpat_new"))
  }

  @Test
  fun `a token never prints itself`() {
    assert(ShopifyAdminToken("shpat_secret").toString() == "***")
    assert("shpat_secret" !in "token=${ShopifyAdminToken("shpat_secret")}")
  }
}
