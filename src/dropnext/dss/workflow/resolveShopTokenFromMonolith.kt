package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure


/**
 * The token store's fallback: a shop whose token is not in memory (a restarted instance, a shop
 * installed before this instance came up) is looked up on the monolith, which keeps every token.
 * `null` when the monolith does not know the shop, has no token for it, or cannot be reached.
 */
suspend fun resolveShopTokenFromMonolith(monolith: MonolithService, shop: ShopDomain): ShopifyAdminToken? =
  when (val result = monolith.getStore(shop.subdomainOnly)) {
    is Success -> result.value?.apiKey
    is Failure -> {
      logMonolithFailure("getStore", result.reason, "subdomain=${shop.subdomainOnly}")
      null
    }
  }
