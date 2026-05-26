package dropnext.dss.workflow

import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.ShipmentMatchResult
import dropnext.dss.lib.shopify.graphql.fulfillment.matchShipmentToFulfillmentOrders
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput


/**
 * Cancels every open Shopify fulfillment on the order and creates new fulfillments from the
 * supplied shipments. Fulfillment orders are resolved automatically by matching
 * `product_variant_id` against fulfillment order line items.
 *
 * Composes the per-shop [shopify] primitives ([ShopifyGraphqlService.cancelFulfillment],
 * [ShopifyGraphqlService.createFulfillmentWithLineItems], [ShopifyGraphqlService.loadOrderForDss]).
 */
suspend fun syncShopifyShipmentsToFulfillments(
  shopify: ShopifyGraphqlService,
  payload: SyncShipmentsWithFulfillmentsRequest,
): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
  val orderGid = orderGid(payload.shopifyOrderId)

  val orderBefore = loadOrder(shopify, orderGid)
    ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

  val existingFulfillmentGids = orderBefore.fulfillments
    .map { it.id }
    .filter { it.isNotBlank() }

  existingFulfillmentGids.forEach { fulfillmentGid ->
    val cancelResult = cancelFulfillment(shopify, fulfillmentGid)
    if (cancelResult is FulfillmentResult.Err) return cancelResult
  }

  val orderAfter = if (existingFulfillmentGids.isEmpty()) {
    orderBefore
  } else {
    loadOrder(shopify, orderGid)
      ?: return FulfillmentResult.Err.NotFound("order not found after cancel")
  }

  val newIds = payload.shipments.flatMap { shipment ->
    when (val result = createFulfillmentForShipment(shopify, orderAfter, shipment)) {
      is FulfillmentResult.Ok -> result.value
      is FulfillmentResult.Err -> return result
    }
  }
  return FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(newIds))
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

private suspend fun createFulfillmentForShipment(
  shopify: ShopifyGraphqlService,
  order: Order,
  shipment: Shipment,
): FulfillmentResult<List<Long>> {
  val fulfillmentOrderToLineItemMap =
    when (val matchResult = matchShipmentToFulfillmentOrders(order, shipment)) {
      is ShipmentMatchResult.Ok -> matchResult.groups
      is ShipmentMatchResult.NotFound -> return FulfillmentResult.Err.NotFound(matchResult.detail)
      is ShipmentMatchResult.UserError -> return FulfillmentResult.Err.UserError(matchResult.messages)
    }

  val fulfillmentOrderIdWithLineItems =
    fulfillmentOrderToLineItemMap.entries.map { (fulfillmentOrder, lineItems) ->
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
  val fulfillmentId = fulfillment?.legacyResourceId?.toLongOrNull()
    ?: fulfillment?.id?.let { legacyIdFromGid(it) }
  return FulfillmentResult.Ok(listOfNotNull(fulfillmentId))
}
