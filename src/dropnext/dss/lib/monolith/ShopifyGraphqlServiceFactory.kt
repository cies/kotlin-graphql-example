package dropnext.dss.lib.monolith

import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService


/**
 * Vends a [ShopifyGraphqlService] for a given [ShopDomain]. Production wires
 * [HttpShopifyGraphqlServiceFactory] (resolves the access token via header → cache → monolith fallback,
 * then constructs an [dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService]); tests wire
 * `FakeShopifyServiceFactory` (returns an in-memory fake).
 */
interface ShopifyGraphqlServiceFactory {
  /**
   * Returns a [ShopifyGraphqlService] for [shop], or `null` when no Admin token is resolvable.
   * When [explicitToken] is supplied (e.g.: directly after an OAuth code exchange, before the token has been cached)
   * it short-circuits both the cache and the monolith fallback.
   */
  suspend fun forShop(shop: ShopDomain, explicitToken: String? = null): ShopifyGraphqlService?
}
