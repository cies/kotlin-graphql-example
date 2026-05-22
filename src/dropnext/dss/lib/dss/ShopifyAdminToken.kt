package dropnext.dss.lib.dss

import dropnext.dss.lib.ktor.shopifyAccessTokenFromHeader
import dropnext.dss.lib.monolith.GetStoreResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.shopifySubdomainShort
import io.ktor.server.application.ApplicationCall

/**
 * Resolving the Shopify Admin API token for a shop without server-side persistence:
 * optional `X-Shopify-Access-Token` on the request, otherwise [ShopAccessTokenCache].
 */
sealed interface ShopifyAdminToken {
  data class Resolved(val token: String) : ShopifyAdminToken

  data object Missing : ShopifyAdminToken
}

fun ApplicationCall.resolveShopifyAdminToken(
  shopSubdomainOrHost: String,
  tokens: ShopAccessTokenCache,
): ShopifyAdminToken {
  val headerToken = request.shopifyAccessTokenFromHeader()
      ?: return fallbackTokenFromCache(shopSubdomainOrHost, tokens)
  return ShopifyAdminToken.Resolved(headerToken)
}

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

private fun ApplicationCall.fallbackTokenFromCache(
  shopSubdomainOrHost: String,
  tokens: ShopAccessTokenCache,
): ShopifyAdminToken {
  val shopNorm =
    normalizeShopDomain(shopSubdomainOrHost)
      ?: return ShopifyAdminToken.Missing
  return shopifyAdminTokenFromEnv(shopNorm, tokens)
}
