package dropnext.dss.domain.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.Order


fun FulfillmentOrderStatus.isOpenForFulfillment(): Boolean =
  when (this) {
    FulfillmentOrderStatus.OPEN,
    FulfillmentOrderStatus.IN_PROGRESS,
    -> true
    else -> false
  }

/**
 * Tracks live remaining quantity (`remainingQuantity`) per fulfillment order line item GID.
 * Sync is additive and does not cancel existing fulfillments, so matching must not use
 * `totalQuantity` (which includes already-fulfilled units).
 */
class FulfillmentQuantityLedger(order: Order) {
  private val availableByLineItemGid = mutableMapOf<String, Int>()

  init {
    order.fulfillmentOrders.edges.forEach { fulfillmentOrderEdge ->
      val fulfillmentOrder = fulfillmentOrderEdge.node
      if (!fulfillmentOrder.status.isOpenForFulfillment()) return@forEach
      fulfillmentOrder.lineItems.edges.forEach { lineEdge ->
        val node = lineEdge.node
        if (node.remainingQuantity > 0) {
          availableByLineItemGid[node.id] = node.remainingQuantity
        }
      }
    }
  }

  fun tryConsume(fulfillmentOrderLineItemGid: String, quantity: Int): Boolean {
    val current = availableByLineItemGid[fulfillmentOrderLineItemGid] ?: return false
    if (quantity > current) return false
    availableByLineItemGid[fulfillmentOrderLineItemGid] = current - quantity
    return true
  }

  fun available(fulfillmentOrderLineItemGid: String): Int =
    availableByLineItemGid[fulfillmentOrderLineItemGid] ?: 0
}
