package dropnext.dss.lib.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of `shop.myshopify.com` → Shopify Admin access token.
 *
 * The DSS has no server-side database; tokens come from env (`DSS_SHOP_ACCESS_TOKENS`),
 * the OAuth callback after install, or a fallback fetch from the monolith. Whichever path
 * fills the cache, every subsequent webhook / DSS REST call reads from here.
 *
 * Backed by a [ConcurrentHashMap] so OAuth callbacks, webhook handlers, and the monolith
 * fallback resolver can write concurrently without data races. Keys are normalised to
 * full `*.myshopify.com` host strings (lowercase).
 */
class ShopAccessTokenCache(initial: Map<String, String> = emptyMap()) {
  private val tokens: ConcurrentHashMap<String, String> = ConcurrentHashMap(initial)

  operator fun get(myshopifyHost: String): String? {
    val direct = tokens[myshopifyHost]
    if (direct != null) return direct
    return tokens.entries.firstOrNull { (key, _) -> key.equals(myshopifyHost, ignoreCase = true) }?.value
  }

  operator fun set(myshopifyHost: String, token: String) {
    tokens[myshopifyHost] = token
  }

  /** Snapshot, primarily for diagnostics. */
  fun snapshot(): Map<String, String> = tokens.toMap()
}
