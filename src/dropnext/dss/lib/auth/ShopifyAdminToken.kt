package dropnext.dss.lib.auth

import dropnext.dss.lib.monolith.GetStoreResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.shopifySubdomainShort


/**
 * Result of resolving a Shopify Admin API token for a single shop. The DSS keeps no
 * server-side database; tokens are looked up in [ShopAccessTokenCache] (env + OAuth callback)
 * with an optional monolith fallback when [shopifyAdminTokenWithMonolithFallback] is used.
 */
sealed interface ShopifyAdminToken {
  data class Resolved(val token: String) : ShopifyAdminToken

  data object Missing : ShopifyAdminToken
}

/** The token string when resolved, or `null` when [ShopifyAdminToken.Missing]. */
val ShopifyAdminToken.tokenOrNull: String?
  get() = (this as? ShopifyAdminToken.Resolved)?.token

/** Looks up a Shopify Admin API token for [shopMyShopifyHost] in the in-memory [tokens] cache only. */
fun shopifyAdminTokenFromEnv(
  shopMyShopifyHost: String,
  tokens: ShopAccessTokenCache,
): ShopifyAdminToken {
  val token = tokens[shopMyShopifyHost.trim().lowercase()]
  return if (token != null) ShopifyAdminToken.Resolved(token) else ShopifyAdminToken.Missing
}

/**
 * Resolves the Shopify Admin API token for [shopMyShopifyHost], first from the in-memory
 * [ShopAccessTokenCache] (fast path, no network), then by calling
 * [MonolithService.getStore] if a [monolith] is configured.
 *
 * When the monolith supplies a token it is cached into [tokens] so subsequent calls are fast.
 *
 * The token value is never logged; only the store-id and subdomain are.
 */
suspend fun shopifyAdminTokenWithMonolithFallback(
  shopMyShopifyHost: String,
  tokens: ShopAccessTokenCache,
  monolith: MonolithService?,
): ShopifyAdminToken {
  val fast = shopifyAdminTokenFromEnv(shopMyShopifyHost, tokens)
  if (fast is ShopifyAdminToken.Resolved) return fast
  if (monolith == null) return ShopifyAdminToken.Missing
  val subdomain = shopifySubdomainShort(shopMyShopifyHost)
  return when (val result = monolith.getStore(subdomain)) {
    is GetStoreResult.Ok -> {
      val token = result.apiKey ?: return ShopifyAdminToken.Missing
      tokens[shopMyShopifyHost] = token
      ShopifyAdminToken.Resolved(token)
    }

    is GetStoreResult.NotFound -> ShopifyAdminToken.Missing
    is GetStoreResult.Error -> {
      logMonolithFailure("getStore", result.status, result.parsed, "subdomain=$subdomain")
      ShopifyAdminToken.Missing
    }
  }
}
