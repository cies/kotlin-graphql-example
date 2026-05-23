package dropnext.dss.handler

import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.dss.ShopifyAdminToken
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.ktor.shopifyAccessTokenFromHeader
import dropnext.dss.lib.monolith.MonolithService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond


/**
 * Resolves the Shopify Admin token for [shopMyShopifyHost] honoring (in order)
 * the `X-Shopify-Access-Token` request header, the in-memory token cache, and the
 * optional monolith fallback. Returns [ShopifyAdminToken.Missing] when every lookup
 * misses so callers can decide how to respond.
 */
suspend fun ApplicationCall.resolveShopifyAdminToken(
  shopMyShopifyHost: String,
  tokens: ShopAccessTokenCache,
  monolith: MonolithService?,
): ShopifyAdminToken {
  request.shopifyAccessTokenFromHeader()?.let { return ShopifyAdminToken.Resolved(it) }
  return shopifyAdminTokenWithMonolithFallback(shopMyShopifyHost, tokens, monolith)
}

/** Standard 401 JSON reply when the Shopify Admin token cannot be resolved. */
suspend fun ApplicationCall.respondMissingShopifyAdminToken() {
  respond(
    HttpStatusCode.Unauthorized,
    ErrorResponse(
      error = "missing Shopify Admin token: use header X-Shopify-Access-Token or configure DSS_SHOP_ACCESS_TOKENS",
    ),
  )
}
