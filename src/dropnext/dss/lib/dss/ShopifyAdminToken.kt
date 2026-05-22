package dropnext.dss.lib.dss

import dropnext.dss.config.DssAppConfig
import dropnext.dss.lib.ktor.shopifyAccessTokenFromHeader
import dropnext.dss.lib.monolith.GetStoreResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.shopifySubdomainShort
import io.ktor.server.application.ApplicationCall

/**
 * Resolving the Shopify Admin API token for a shop without server-side persistence:
 * optional `X-Shopify-Access-Token` on the request, otherwise [DssAppConfig.shopAccessTokens].
 */
sealed interface ShopifyAdminToken {
  data class Resolved(val token: String) : ShopifyAdminToken

  data object Missing : ShopifyAdminToken
}

fun ApplicationCall.resolveShopifyAdminToken(
  shopSubdomainOrHost: String,
  dssConfig: DssAppConfig,
): ShopifyAdminToken {
  val headerToken = request.shopifyAccessTokenFromHeader()
      ?: return fallbackTokenFromConfig(shopSubdomainOrHost, dssConfig)
  return ShopifyAdminToken.Resolved(headerToken)
}

fun shopifyAdminTokenFromEnv(
  shopMyShopifyHost: String,
  dssConfig: DssAppConfig,
): ShopifyAdminToken {
  val key = shopMyShopifyHost.trim().lowercase()
  val token = dssConfig.shopAccessTokens[key]
    ?: dssConfig.shopAccessTokens.entries.firstOrNull { (k, _) ->
      k.equals(key, ignoreCase = true)
    }?.value
  return if (token != null) ShopifyAdminToken.Resolved(token) else ShopifyAdminToken.Missing
}

/**
 * Resolves the Shopify Admin API token for [shopMyShopifyHost], first from the in-memory
 * [DssAppConfig.shopAccessTokens] map (fast path, no network), then by calling
 * [MonolithService.getStore] if a [monolith] is configured.
 *
 * When the monolith supplies a token it is cached into [DssAppConfig.shopAccessTokens]
 * (which is a [java.util.concurrent.ConcurrentHashMap]) so subsequent calls are fast.
 *
 * The token value is never logged; only the store-id and subdomain are.
 */
suspend fun shopifyAdminTokenWithMonolithFallback(
  shopMyShopifyHost: String,
  dssConfig: DssAppConfig,
  monolith: MonolithService?,
): ShopifyAdminToken {
  val fast = shopifyAdminTokenFromEnv(shopMyShopifyHost, dssConfig)
  if (fast is ShopifyAdminToken.Resolved) return fast
  if (monolith == null) return ShopifyAdminToken.Missing
  val subdomain = shopifySubdomainShort(shopMyShopifyHost)
  return when (val result = monolith.getStore(subdomain)) {
    is GetStoreResult.Ok -> {
      val token = result.apiKey ?: return ShopifyAdminToken.Missing
      dssConfig.shopAccessTokens[shopMyShopifyHost] = token
      ShopifyAdminToken.Resolved(token)
    }

    is GetStoreResult.NotFound -> ShopifyAdminToken.Missing
    is GetStoreResult.Error -> {
      logMonolithFailure("getStore", result.status, result.parsed, "subdomain=$subdomain")
      ShopifyAdminToken.Missing
    }
  }
}

private fun ApplicationCall.fallbackTokenFromConfig(
  shopSubdomainOrHost: String,
  dssConfig: DssAppConfig,
): ShopifyAdminToken {
  val shopNorm =
    normalizeShopDomain(shopSubdomainOrHost)
      ?: return ShopifyAdminToken.Missing
  return shopifyAdminTokenFromEnv(shopNorm, dssConfig)
}
