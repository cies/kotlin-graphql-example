package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.shopify.token.ShopLookup


/**
 * Vends a [ShopifyGraphqlService] for a given [ShopDomain].
 *
 * Production wires [HttpShopifyGraphqlServiceFactory]
 * (resolves the Admin token through the token store, then constructs an [HttpShopifyGraphqlService]);
 * tests wire `FakeShopifyGraphqlServiceFactory` (returns an in-memory fake).
 */
interface ShopifyGraphqlServiceFactory {
  /** [ShopLookup.Missing] when no Admin token is known for [shop], [ShopLookup.Unavailable] when the token source could not be asked. */
  suspend fun forShop(shop: ShopDomain): ShopLookup<ShopifyGraphqlService>
}
