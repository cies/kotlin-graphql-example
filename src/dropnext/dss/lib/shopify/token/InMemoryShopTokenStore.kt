package dropnext.dss.lib.shopify.token

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import java.util.concurrent.ConcurrentHashMap


/**
 * In-memory [ShopTokenStore], as the DSS has no database (it is stateless): basically a cache.
 *
 * Seeded from `DSS_SHOP_ACCESS_TOKENS`, filled by the OAuth callback and `PUT /stores/api-key`,
 * and —on a miss— by [fallback], which production wires to a monolith lookup so a restarted instance recovers its tokens.
 *
 * Backed by a [ConcurrentHashMap] so callbacks, webhook handlers and the fallback can write concurrently.
 * Keys are canonical [ShopDomain] values, so case-insensitive lookups are unnecessary.
 */
class InMemoryShopTokenStore(
  initial: Map<ShopDomain, ShopifyAdminToken> = emptyMap(),
  private val fallback: suspend (ShopDomain) -> ShopifyAdminToken? = { null },
) : ShopTokenStore {
  private val tokens: ConcurrentHashMap<ShopDomain, ShopifyAdminToken> = ConcurrentHashMap(initial)

  override suspend fun resolve(shop: ShopDomain): ShopifyAdminToken? {
    tokens[shop]?.let { return it }
    val fetched = fallback(shop) ?: return null
    // `putIfAbsent`, not `put`: a `remember` that landed while the fallback was in flight (an OAuth
    // callback, a `PUT /stores/api-key`) is newer than what the monolith answered and must win.
    return tokens.putIfAbsent(shop, fetched) ?: fetched
  }

  override fun remember(shop: ShopDomain, token: ShopifyAdminToken) {
    tokens[shop] = token
  }

  override fun forget(shop: ShopDomain) {
    tokens.remove(shop)
  }

  /** What is cached right now, without consulting the fallback — for diagnostics and tests. */
  fun cached(shop: ShopDomain): ShopifyAdminToken? = tokens[shop]
}
