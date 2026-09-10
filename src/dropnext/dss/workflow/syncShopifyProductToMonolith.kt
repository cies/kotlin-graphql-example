package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.mapper.toProductVariantItems
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Mirrors one Shopify product into the monolith after a `products/create` or `products/update` webhook:
 * loads it (the webhook body is id-only), maps its variants, and upserts them.
 * A webhook is answered `200` whatever happens here, so failures only go to the log.
 */
suspend fun syncShopifyProductToMonolith(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  productGid: String,
) {
  val shopProduct = when (val loaded = shopify.productById(productGid)) {
    is Failure -> {
      log.warn { "Webhook product: could not load productGid=$productGid error=${loaded.reason.message}" }
      return
    }
    is Success -> loaded.value ?: run {
      log.info { "Webhook product: Shopify has no product productGid=$productGid (deleted meanwhile?)" }
      return
    }
  }
  log.info { "Webhook product loaded id=$productGid title=${shopProduct.product.title}" }
  val variantItems = shopProduct.product.toProductVariantItems(shopProduct.shopCurrencyCode)
  if (variantItems.isEmpty()) return

  val request = UpsertProductVariantsRequest(
    shopifySubdomain = shopify.shop.subdomainOnly,
    productVariants = variantItems,
  )
  when (val result = monolith.upsertProductVariants(request)) {
    is Success ->
      log.info { "Monolith upsert variants ok: ${result.value} upserted shop=${shopify.shop.normalizedShopifyHost}" }
    is Failure ->
      logMonolithFailure("upsertProductVariants", result.reason, "shop=${shopify.shop.normalizedShopifyHost}")
  }
}
