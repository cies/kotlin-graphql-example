package dropnext.dss.lib.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.dss.lib.dss.dto.Shipment
import dropnext.dss.lib.dss.dto.ShipmentLineItem

/** Result of matching a monolith [Shipment] to open Shopify fulfillment order line items. */
sealed interface ShipmentMatchResult {
  data class Ok(
    val groups: Map<FulfillmentOrder, List<FulfillmentOrderLineItemInput>>,
  ) : ShipmentMatchResult

  data class NotFound(val detail: String) : ShipmentMatchResult

  data class UserError(val messages: List<String>) : ShipmentMatchResult
}

fun FulfillmentOrderStatus.isOpenForFulfillment(): Boolean =
  when (this) {
    FulfillmentOrderStatus.OPEN,
    FulfillmentOrderStatus.IN_PROGRESS,
    -> true
    else -> false
  }

/**
 * Maps shipment line items to open fulfillment orders. Fails when a variant cannot be matched,
 * when requested quantity exceeds [remainingQuantity], or when no fulfillable lines remain.
 */
fun matchShipmentToFulfillmentOrders(
  order: Order,
  shipment: Shipment,
): ShipmentMatchResult {
  val foGroups = mutableMapOf<FulfillmentOrder, MutableList<FulfillmentOrderLineItemInput>>()

  for (req in shipment.lineItems) {
    when (val lineResult = matchShipmentLineItem(order, shipment, req)) {
      is LineMatchResult.Matched -> {
        foGroups
          .getOrPut(lineResult.fulfillmentOrder) { mutableListOf() }
          .add(lineResult.input)
      }
      is LineMatchResult.NotFound ->
        return ShipmentMatchResult.NotFound(lineResult.detail)
      is LineMatchResult.UserError ->
        return ShipmentMatchResult.UserError(lineResult.messages)
    }
  }

  if (foGroups.isEmpty()) {
    return ShipmentMatchResult.NotFound(
      "no matching open fulfillment order line items for shipment tracking=${shipment.trackingNumber}",
    )
  }
  return ShipmentMatchResult.Ok(foGroups)
}

private sealed interface LineMatchResult {
  data class Matched(
    val fulfillmentOrder: FulfillmentOrder,
    val input: FulfillmentOrderLineItemInput,
  ) : LineMatchResult

  data class NotFound(val detail: String) : LineMatchResult

  data class UserError(val messages: List<String>) : LineMatchResult
}

private fun matchShipmentLineItem(
  order: Order,
  shipment: Shipment,
  req: ShipmentLineItem,
): LineMatchResult {
  for (foe in order.fulfillmentOrders.edges) {
    val fo = foe.node
    if (!fo.status.isOpenForFulfillment()) continue
    val match =
      fo.lineItems.edges
        .map { it.node }
        .find { node ->
          node.variant?.legacyResourceId?.toLongOrNull() == req.productVariantId
        }
        ?: continue
    if (req.quantity > match.remainingQuantity) {
      return LineMatchResult.UserError(
        listOf(
          "variant ${req.productVariantId} requested quantity ${req.quantity} exceeds " +
            "remaining ${match.remainingQuantity} on fulfillment order",
        ),
      )
    }
    if (req.quantity <= 0) {
      return LineMatchResult.UserError(
        listOf("variant ${req.productVariantId} quantity must be positive"),
      )
    }
    return LineMatchResult.Matched(
      fulfillmentOrder = fo,
      input = FulfillmentOrderLineItemInput(id = match.id, quantity = req.quantity),
    )
  }
  return LineMatchResult.NotFound(
    "no open fulfillment order line item for variant ${req.productVariantId} " +
      "(tracking=${shipment.trackingNumber})",
  )
}
