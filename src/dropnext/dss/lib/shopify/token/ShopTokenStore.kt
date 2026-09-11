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
  /** The token for [shop], or `null` when none is known anywhere. */
  suspend fun resolve(shop: ShopDomain): ShopifyAdminToken?

  fun remember(shop: ShopDomain, token: ShopifyAdminToken)

  /**
   * Drops what is cached for [shop], so the next [resolve] asks the source of truth again. Called
   * when Shopify rejects the token: the monolith may hold a newer one from a reinstall this
   * instance never saw, and without this the stale token would be retried until a restart.
   */
  fun forget(shop: ShopDomain)
}
