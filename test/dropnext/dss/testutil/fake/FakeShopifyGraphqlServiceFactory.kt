package dropnext.dss.testutil.fake

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopTokenStore


/**
 * Test [ShopifyGraphqlServiceFactory] that returns a fixed [service] for every shop. Most tests have
 * one shop in play and configure a single [FakeShopifyGraphqlService] — `forShop` then ignores
 * the [shop] argument and hands back that instance.
 *
 * Pass `service = null` to simulate the "no Admin token resolvable" path (handlers return
 * `DssError.MissingShopifyAdminToken` / log "no Admin token").
 *
 * Pass [tokens] to keep the production factory's contract, no token in the store means no service: a
 * handler test then proves the token was remembered before the service was asked for, which the OAuth
 * callback relies on. Without it the store is ignored, which is what a test that seeds no token wants.
 */
class FakeShopifyGraphqlServiceFactory(
  private val service: ShopifyGraphqlService? = FakeShopifyGraphqlService(),
  private val tokens: ShopTokenStore? = null,
) : ShopifyGraphqlServiceFactory {
  override suspend fun forShop(shop: ShopDomain): ShopifyGraphqlService? {
    if (tokens != null && tokens.resolve(shop) == null) return null
    return service
  }
}
