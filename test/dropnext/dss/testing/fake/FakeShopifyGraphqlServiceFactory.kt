package dropnext.dss.testing.fake

import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService


/**
 * Test [ShopifyGraphqlServiceFactory] that returns a fixed [service] for every shop. Most tests have
 * one shop in play and configure a single [FakeShopifyGraphqlService] — `forShop` then ignores
 * the [shop] argument and hands back that instance.
 *
 * Pass `service = null` to simulate the "no Admin token resolvable" path (handlers return
 * `DssError.MissingShopifyAdminToken` / log "no Admin token").
 */
class FakeShopifyGraphqlServiceFactory(
  private val service: ShopifyGraphqlService? = FakeShopifyGraphqlService(),
) : ShopifyGraphqlServiceFactory {
  override suspend fun forShop(shop: ShopDomain, explicitToken: String?): ShopifyGraphqlService? = service
}
