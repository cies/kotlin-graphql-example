package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopDomain


/**
 * Vends a [ShopifyGraphqlService] for a given [ShopDomain].
 *
 * Production wires [HttpShopifyGraphqlServiceFactory]
 * (resolves the Admin token through the token store, then constructs an [HttpShopifyGraphqlService]);
 * tests wire `FakeShopifyGraphqlServiceFactory` (returns an in-memory fake).
 */
interface ShopifyGraphqlServiceFactory {
  /** Returns a [ShopifyGraphqlService] for [shop], or `null` when no Admin token is resolvable. */
  suspend fun forShop(shop: ShopDomain): ShopifyGraphqlService?
}
