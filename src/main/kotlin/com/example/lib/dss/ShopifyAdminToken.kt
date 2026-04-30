package com.example.lib.dss

import com.example.lib.shopify.normalizeShopDomain
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
  val headerToken =
    request.headers["X-Shopify-Access-Token"]?.trim()?.takeIf { it.isNotEmpty() }
      ?: return fallbackTokenFromConfig(shopSubdomainOrHost, dssConfig)
  return ShopifyAdminToken.Resolved(headerToken)
}

fun shopifyAdminTokenForNormalizedShop(
  shopMyshopifyHost: String,
  dssConfig: DssAppConfig,
): ShopifyAdminToken {
  val key = shopMyshopifyHost.trim().lowercase()
  val token =
    dssConfig.shopAccessTokens[key]
      ?: dssConfig.shopAccessTokens.entries.firstOrNull { (k, _) -> k.equals(key, ignoreCase = true) }?.value
  return if (token != null) ShopifyAdminToken.Resolved(token) else ShopifyAdminToken.Missing
}

private fun ApplicationCall.fallbackTokenFromConfig(
  shopSubdomainOrHost: String,
  dssConfig: DssAppConfig,
): ShopifyAdminToken {
  val shopNorm =
    normalizeShopDomain(shopSubdomainOrHost)
      ?: return ShopifyAdminToken.Missing
  return shopifyAdminTokenForNormalizedShop(shopNorm, dssConfig)
}
