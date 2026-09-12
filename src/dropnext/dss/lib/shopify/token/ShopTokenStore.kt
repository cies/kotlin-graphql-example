package dropnext.dss.lib.shopify.token

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken


/**
 * Where a shop's Admin token comes from and goes to.
 *
 * The one place that hands out tokens, so the OAuth callback, the monolith's `PUT /stores/api-key`
 * and the Graphql service factory all agree on what "the token for this shop" is.
 */
interface ShopTokenStore {
  suspend fun resolve(shop: ShopDomain): ShopLookup<ShopifyAdminToken>

  fun remember(shop: ShopDomain, token: ShopifyAdminToken)

  /**
   * Drops the [token] Shopify rejected for [shop], so the next [resolve] asks the source of truth again: the monolith
   * may hold a newer one from a reinstall this instance never saw, and without this the stale token would be retried
   * until a restart.
   *
   * The token is a parameter because only that one may go. A request that started out with the old token can get its
   * `401` after a newer one was remembered (a reinstall's OAuth callback, a `PUT /stores/api-key`), and evicting by shop
   * alone would throw the working token away together with the dead one.
   */
  fun forget(shop: ShopDomain, token: ShopifyAdminToken)
}
