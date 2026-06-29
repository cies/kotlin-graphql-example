package dropnext.dss.workflow

import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.DryRunResult
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.ShipmentMatchResult
import dropnext.dss.lib.shopify.graphql.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.fulfillment.SkippedShipmentLine
import dropnext.dss.lib.shopify.graphql.fulfillment.dryRunAllShipments
import dropnext.dss.lib.shopify.graphql.fulfillment.matchShipmentToFulfillmentOrders
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Cancels every open Shopify fulfillment on the order and creates new fulfillments from the
 * supplied shipments. Fulfillment orders are resolved automatically by matching
 * `product_variant_id` against fulfillment order line items.
 *
 * Validates the full payload (dry-run) before any cancel mutations. Reloads order state after
 * each successful create so subsequent shipments see fresh remaining quantities.
 *
 * Composes the per-shop [shopify] primitives ([ShopifyGraphqlService.cancelFulfillment],
 * [ShopifyGraphqlService.createFulfillmentWithLineItems], [ShopifyGraphqlService.loadOrderForDss]).
 */
suspend fun syncShopifyShipmentsToFulfillments(
  shopify: ShopifyGraphqlService,
  payload: SyncShipmentsWithFulfillmentsRequest,
): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
  val orderGid = orderGid(payload.shopifyOrderId)
  val shop = payload.shopifySubdomain
  val orderId = payload.shopifyOrderId

  val orderBefore = loadOrder(shopify, orderGid)
    ?: return FulfillmentResult.Err.NotFound("order $orderId not found")

  when (val dryRun = dryRunAllShipments(orderBefore, payload.shipments)) {
    is DryRunResult.UserError -> return FulfillmentResult.Err.UserError(dryRun.messages)
    is DryRunResult.Ok -> Unit
  }

  val existingFulfillmentGids = orderBefore.fulfillments
    .map { it.id }
    .filter { it.isNotBlank() }
  val canceledCount = existingFulfillmentGids.size

  existingFulfillmentGids.forEach { fulfillmentGid ->
    val cancelResult = cancelFulfillment(shopify, fulfillmentGid)
    if (cancelResult is FulfillmentResult.Err) return cancelResult
  }

  var order = if (existingFulfillmentGids.isEmpty()) {
    orderBefore
  } else {
    loadOrder(shopify, orderGid)
      ?: return FulfillmentResult.Err.NotFound("order not found after cancel")
  }

  val newFulfillmentIds = mutableListOf<Long>()
  var totalSkippedLines = 0
  var skippedShipments = 0

  for (shipment in payload.shipments) {
    when (val matchResult = matchShipmentToFulfillmentOrders(order, shipment)) {
      is ShipmentMatchResult.UserError -> return FulfillmentResult.Err.UserError(matchResult.messages)
      is ShipmentMatchResult.Ok -> {
        totalSkippedLines += matchResult.skipped.size
        matchResult.skipped.forEach { skipped ->
          logSkippedLine(shop, orderId, skipped)
        }

        if (matchResult.groups.isEmpty()) {
          skippedShipments++
          log.warn {
            "sync-shipments skipped shipment shop=$shop orderId=$orderId " +
              "tracking=${shipment.trackingNumber} reason=all_lines_unmatched"
          }
          continue
        }

        when (val createResult = createFulfillmentForGroups(shopify, shipment, matchResult.groups)) {
          is FulfillmentResult.Err -> return createResult
          is FulfillmentResult.Ok -> {
            val createdIds = createResult.value
            newFulfillmentIds.addAll(createdIds)
            order = loadOrder(shopify, orderGid)
              ?: return FulfillmentResult.Err.Network(
                buildString {
                  append("fulfillmentId=${createdIds.lastOrNull()} created but order reload failed")
                  append(" — manual verify required")
                  append(" (shop=$shop orderId=$orderId)")
                },
              )
          }
        }
      }
    }
  }

  log.info {
    "sync-shipments orderId=$orderId shop=$shop canceled=$canceledCount " +
      "created=${newFulfillmentIds.size} skippedLines=$totalSkippedLines " +
      "skippedShipments=$skippedShipments fulfillmentIds=$newFulfillmentIds"
  }

  return FulfillmentResult.Ok(
    SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = newFulfillmentIds),
  )
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

private suspend fun loadOrder(shopify: ShopifyGraphqlService, orderGid: String): Order? {
  val r = runCatching { shopify.loadOrderForDss(orderGid) }.getOrElse { return null }
  return r.data?.order
}

private suspend fun cancelFulfillment(
  shopify: ShopifyGraphqlService,
  fulfillmentGid: String,
): FulfillmentResult<Unit> {
  val r = runCatching { shopify.cancelFulfillment(fulfillmentGid) }
    .getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
  val errs = r.data?.fulfillmentCancel?.userErrors.orEmpty()
  // "already canceled" errors are no-ops; everything else is a real failure.
  val realErrs = errs.filter { !it.message.contains("already", ignoreCase = true) }
  if (realErrs.isNotEmpty()) {
    return FulfillmentResult.Err.UserError(realErrs.map { it.message })
  }
  val graphqlErrors = r.errors
  if (!graphqlErrors.isNullOrEmpty()) {
    return FulfillmentResult.Err.GraphqlError(graphqlErrors.joinToString("; ") { it.message })
  }
  return FulfillmentResult.Ok(Unit)
}

private suspend fun createFulfillmentForGroups(
  shopify: ShopifyGraphqlService,
  shipment: Shipment,
  groups: Map<FulfillmentOrder, List<FulfillmentOrderLineItemInput>>,
): FulfillmentResult<List<Long>> {
  val fulfillmentOrderIdWithLineItems =
    groups.entries.map { (fulfillmentOrder, lineItems) ->
      FulfillmentOrderLineItemsInput(
        fulfillmentOrderId = fulfillmentOrder.id,
        fulfillmentOrderLineItems = lineItems,
      )
    }

  val tracking = FulfillmentTrackingInput(
    company = shipment.carrier,
    number = shipment.trackingNumber,
    url = shipment.trackingUrl,
  )

  val response = runCatching {
    shopify.createFulfillmentWithLineItems(
      lineItemsByFulfillmentOrder = fulfillmentOrderIdWithLineItems,
      tracking = tracking,
      notifyCustomer = false,
    )
  }.getOrElse { return FulfillmentResult.Err.Network(it.message ?: "network error") }

  val createErrs = response.data?.fulfillmentCreate?.userErrors.orEmpty()
  if (createErrs.isNotEmpty()) {
    return FulfillmentResult.Err.UserError(createErrs.map { it.message })
  }
  val graphqlErrors = response.errors
  if (!graphqlErrors.isNullOrEmpty()) {
    return FulfillmentResult.Err.GraphqlError(graphqlErrors.joinToString("; ") { it.message })
  }

  val fulfillment = response.data?.fulfillmentCreate?.fulfillment
    ?: return FulfillmentResult.Err.UserError(listOf("fulfillment missing in response"))

  val fulfillmentId = fulfillment.legacyResourceId.toLongOrNull()
    ?: fulfillment.id.let { legacyIdFromGid(it) }
  if (fulfillmentId == null) {
    log.warn {
      "sync-shipments could not resolve fulfillment id from response gid=${fulfillment.id} " +
        "tracking=${shipment.trackingNumber}"
    }
  }
  return FulfillmentResult.Ok(listOfNotNull(fulfillmentId))
}
