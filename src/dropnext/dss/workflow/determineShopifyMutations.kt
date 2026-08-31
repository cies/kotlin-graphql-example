package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.DryRunResult
import dropnext.dss.lib.shopify.graphql.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.fulfillment.SkippedShipmentLine
import dropnext.dss.lib.shopify.graphql.fulfillment.dryRunAllShipments
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.getorderfordss.Order
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** Read-only: loads Shopify data, then [calculateShopifyMutations]. */
suspend fun determineShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: Long,
  shipments: List<Shipment>,
): Result<List<ShopifyMutation>, DetermineShopifyMutationsError> {
  val order = when (val loaded = loadShopifyOrder(shopifyGqlService, shopifyOrderId)) {
    is Failure -> return Failure(loaded.reason)
    is Success -> loaded.value
  }
  val calculated = calculateShopifyMutations(order, shipments)
  if (calculated is Success) {
    logSkippedShipmentLines(
      shopifySubdomain = shopifyGqlService.shop.subdomainOnly,
      shopifyOrderId = shopifyOrderId,
      order = order,
      shipments = shipments,
    )
  }
  return calculated
}

private suspend fun loadShopifyOrder(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: Long,
): Result<Order, DetermineShopifyMutationsError> {
  val response = try {
    shopifyGqlService.loadOrderForDss(orderGid(shopifyOrderId))
  } catch (e: Exception) {
    return Failure(DetermineShopifyMutationsError.Network(e.message ?: "network error"))
  }
  val order = response.data?.order
  val graphqlErrors = response.errors
  if (order == null && !graphqlErrors.isNullOrEmpty()) {
    return Failure(
      DetermineShopifyMutationsError.GraphqlError(
        graphqlErrors.joinToString("; ") { it.message },
      ),
    )
  }
  if (order == null) {
    return Failure(DetermineShopifyMutationsError.NotFound("order $shopifyOrderId not found"))
  }
  return Success(order)
}

private fun logSkippedShipmentLines(
  shopifySubdomain: String,
  shopifyOrderId: Long,
  order: Order,
  shipments: List<Shipment>,
) {
  when (val match = dryRunAllShipments(order, shipments)) {
    is DryRunResult.UserError -> return
    is DryRunResult.Ok -> {
      match.perShipment.forEachIndexed { index, shipmentMatch ->
        shipmentMatch.skipped.forEach { skipped ->
          logSkippedLine(shopifySubdomain, shopifyOrderId, skipped)
        }
        if (shipmentMatch.groups.isEmpty()) {
          val tracking = shipments.getOrNull(index)?.trackingNumber.orEmpty()
          log.warn {
            "sync-shipments skipped shipment shop=$shopifySubdomain orderId=$shopifyOrderId " +
              "tracking=$tracking reason=all_lines_unmatched"
          }
        }
      }
    }
  }
}

private fun logSkippedLine(shop: String, orderId: Long, skipped: SkippedShipmentLine) {
  log.warn {
    "sync-shipments skipped line shop=$shop orderId=$orderId " +
      "tracking=${skipped.trackingNumber} variant=${skipped.productVariantId} " +
      "reason=${skipped.reason.logLabel()} qty=${skipped.quantity}"
  }
}

private fun SkipReason.logLabel(): String =
  when (this) {
    SkipReason.NO_OPEN_FO -> "no_open_fo"
    SkipReason.VARIANT_NOT_FOUND -> "variant_not_found"
    SkipReason.ZERO_REMAINING -> "zero_remaining"
  }
