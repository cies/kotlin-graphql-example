package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The variants of a product Shopify deleted get deleted (soft-deleted) in the monolith too;
 * the ids come straight from the webhook body.
 */
suspend fun deleteShopifyProductFromMonolith(
  monolith: MonolithService,
  shop: ShopDomain,
  variantIds: List<ShopifyVariantId>,
) {
  if (variantIds.isEmpty()) return
  val request = DeleteProductVariantsRequest(
    shopifySubdomain = shop.subdomainOnly,
    productVariantIds = variantIds.map { it.value },
  )
  when (val result = monolith.deleteProductVariants(request)) {
    is Success ->
      log.info { "Monolith delete variants ok: ${result.value} deleted shop=${shop.normalizedShopifyHost}" }
    is Failure ->
      logMonolithFailure("deleteProductVariants", result.reason, "shop=${shop.normalizedShopifyHost}")
  }
}
