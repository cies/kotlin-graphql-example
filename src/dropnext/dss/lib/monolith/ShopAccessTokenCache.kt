package dropnext.dss.lib.monolith

import dropnext.dss.lib.shopify.ShopDomain
import java.util.concurrent.ConcurrentHashMap


/**
 * In-memory cache of [ShopDomain] → Shopify Admin access token.
 *
 * The DSS has no server-side database; tokens come from env (`DSS_SHOP_ACCESS_TOKENS`),
 * the OAuth callback after installation, or a fallback fetch from the monolith.
 * Whichever path fills the cache, every subsequent webhook / DSS REST call reads from here.
 *
 * Backed by a [ConcurrentHashMap] so OAuth callbacks, webhook handlers, and the monolith
 * fallback resolver can write concurrently without data races. Keys are canonical [ShopDomain]
 * values, so case-insensitive lookups are unnecessary.
 */
class ShopAccessTokenCache(initial: Map<ShopDomain, String> = emptyMap()) {
  private val tokens: ConcurrentHashMap<ShopDomain, String> = ConcurrentHashMap(initial)

  operator fun get(shop: ShopDomain): String? = tokens[shop]

  operator fun set(shop: ShopDomain, token: String) {
    tokens[shop] = token
  }
}
