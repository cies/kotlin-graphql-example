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
}
