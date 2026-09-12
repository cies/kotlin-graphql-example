package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.token.ShopLookup


/**
 * The token store's fallback: a shop whose token is not in memory (a restarted instance, a shop
 * installed before this instance came up) is looked up on the monolith, which keeps every token.
 *
 * [ShopLookup.Missing] when the monolith does not know the shop or holds no token for it. [ShopLookup.Unavailable]
 * when the monolith could not be asked, which is not the same answer: a webhook handled as if its shop had no token is
 * acknowledged and lost, while the shop's token was there all along.
 */
suspend fun resolveShopTokenFromMonolith(monolith: MonolithService, shop: ShopDomain): ShopLookup<ShopifyAdminToken> =
  when (val result = monolith.getStore(shop.subdomainOnly)) {
    is Success -> result.value?.apiKey?.let { ShopLookup.Found(it) } ?: ShopLookup.Missing
    is Failure -> {
      logMonolithFailure("getStore", result.reason, "subdomain=${shop.subdomainOnly}")
      ShopLookup.Unavailable
    }
  }
