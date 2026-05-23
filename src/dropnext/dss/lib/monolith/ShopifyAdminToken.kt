package dropnext.dss.lib.monolith

import dropnext.dss.shopify.shopifySubdomainShort


/**
 * Result of resolving a Shopify Admin API token for a single shop. The DSS keeps no
 * server-side database; tokens are looked up in [ShopAccessTokenCache] (env + OAuth callback)
 * with an optional monolith fallback when [shopifyAdminTokenWithMonolithFallback] is used.
 */
// TODO(cies): How is this better than allowing `null`?
sealed interface ShopifyAdminToken {
  data class Resolved(val token: String) : ShopifyAdminToken

  data object Missing : ShopifyAdminToken
}

/** The token string when resolved, or `null` when [ShopifyAdminToken.Missing]. */
val ShopifyAdminToken.tokenOrNull: String?
  get() = (this as? ShopifyAdminToken.Resolved)?.token

/** Looks up a Shopify Admin API token for [shopMyShopifyHost] in the in-memory [tokens] cache only. */
// TODO(cies): why is this called "from env"?
// TODO: why is it sometimes called AccessToken and sometime AdminToken?
private fun shopifyAdminTokenFromEnv(
  shopMyShopifyHost: String,
  tokens: ShopAccessTokenCache,
): ShopifyAdminToken =
  tokens[shopMyShopifyHost.trim().lowercase()]?.let { ShopifyAdminToken.Resolved(it) }
    ?: ShopifyAdminToken.Missing

/**
 * Resolves the Shopify Admin API token for [shopMyShopifyHost], first from the in-memory
 * [ShopAccessTokenCache] (fast path, no network), then by calling
 * [MonolithService.getStore] if a [monolith] is configured.
 *
 * When the monolith supplies a token, it is cached into [tokens] so later calls are fast.
 *
 * The token value is never logged; only the store-id and subdomain are.
 */
// TODO(cies): make this part of the monolith service (class with a constructor that creates one)
suspend fun shopifyAdminTokenWithMonolithFallback(
  shopMyShopifyHost: String,
  tokens: ShopAccessTokenCache,
  monolith: MonolithService?, // TODO(cies): why can this be `null`?
): ShopifyAdminToken {
  val fast = shopifyAdminTokenFromEnv(shopMyShopifyHost, tokens) // TODO: inline
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
