package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem

enum class SkipReason {
  NO_OPEN_FO,
  VARIANT_NOT_FOUND,
  ZERO_REMAINING,
}

data class SkippedShipmentLine(
  val productVariantId: Long,
  val quantity: Int,
  val reason: SkipReason,
  val trackingNumber: String,
)

/** Result of matching a monolith [Shipment] to open Shopify fulfillment order line items. */
sealed interface ShipmentMatchResult {
  data class Ok(
    val groups: Map<FulfillmentOrder, List<FulfillmentOrderLineItemInput>>,
    val skipped: List<SkippedShipmentLine>,
  ) : ShipmentMatchResult

  data class UserError(val messages: List<String>) : ShipmentMatchResult
}

sealed interface DryRunResult {
  data class Ok(
    val perShipment: List<ShipmentMatchResult.Ok>,
    val totalSkipped: Int,
  ) : DryRunResult

  data class UserError(val messages: List<String>) : DryRunResult
}

fun FulfillmentOrderStatus.isOpenForFulfillment(): Boolean =
  when (this) {
    FulfillmentOrderStatus.OPEN,
    FulfillmentOrderStatus.IN_PROGRESS,
    -> true
    else -> false
  }

/**
 * Tracks post-cancel capacity ([totalQuantity]) per fulfillment order line item GID.
 * Sync cancels existing fulfillments before create, so matching must not use live
 * [remainingQuantity] (which is already reduced by those fulfillments).
 */
class FulfillmentQuantityLedger(order: Order) {
  private val availableByLineItemGid = mutableMapOf<String, Int>()

  init {
    for (fulfillmentOrderEdge in order.fulfillmentOrders.edges) {
      val fulfillmentOrder = fulfillmentOrderEdge.node
      if (!fulfillmentOrder.status.isOpenForFulfillment()) continue
      for (lineEdge in fulfillmentOrder.lineItems.edges) {
        val node = lineEdge.node
        if (node.totalQuantity > 0) {
          availableByLineItemGid[node.id] = node.totalQuantity
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

/** Aggregates duplicate [ShipmentLineItem.productVariantId] rows within a shipment (sum quantities). */
fun normalizeShipmentLineItems(shipment: Shipment): List<ShipmentLineItem> =
  shipment.lineItems
    .groupBy { it.productVariantId }
    .map { (variantId, items) ->
      ShipmentLineItem(
        productVariantId = variantId,
        quantity = items.sumOf { it.quantity },
      )
    }

/**
 * Matches all shipments against a single in-memory quantity ledger seeded from
 * [totalQuantity] (post-cancel capacity). Catches cross-shipment over-allocation before any
 * Shopify mutations.
 */
fun dryRunAllShipments(currentShopifyOrder: Order, shipments: List<Shipment>): DryRunResult {
  val ledger = FulfillmentQuantityLedger(currentShopifyOrder)
  val perShipment = mutableListOf<ShipmentMatchResult.Ok>()
  var totalSkipped = 0

  shipments.forEach { shipment ->
    when (val result = matchShipmentToFulfillmentOrders(currentShopifyOrder, shipment, ledger)) {
      is ShipmentMatchResult.Ok -> {
        perShipment.add(result)
        totalSkipped += result.skipped.size
      }
      is ShipmentMatchResult.UserError -> return DryRunResult.UserError(result.messages)
    }
  }
  return DryRunResult.Ok(perShipment, totalSkipped)
}

/**
 * Maps shipment line items to open fulfillment orders. Unmatched variants are skipped (partial
 * match). Returns [ShipmentMatchResult.UserError] when requested quantity exceeds available
 * post-cancel capacity ([totalQuantity]). Matching always uses post-cancel capacity.
 */
fun matchShipmentToFulfillmentOrders(
  order: Order,
  shipment: Shipment,
  ledger: FulfillmentQuantityLedger? = null,
): ShipmentMatchResult {
  val quantityLedger = ledger ?: FulfillmentQuantityLedger(order)
  val fulfillmentOrderGroups =
    mutableMapOf<FulfillmentOrder, MutableList<FulfillmentOrderLineItemInput>>()
  val skipped = mutableListOf<SkippedShipmentLine>()

  normalizeShipmentLineItems(shipment).forEach { shipmentLineItem ->
    if (shipmentLineItem.quantity <= 0) {
      return ShipmentMatchResult.UserError(
        listOf("variant ${shipmentLineItem.productVariantId} quantity must be positive"),
      )
    }

    when (val lineResult = matchShipmentLineItem(order, shipmentLineItem, quantityLedger)) {
      is LineMatchResult.Matched -> {
        fulfillmentOrderGroups
          .getOrPut(lineResult.fulfillmentOrder) { mutableListOf() }
          .add(lineResult.input)
      }
      is LineMatchResult.Skip ->
        skipped.add(
          SkippedShipmentLine(
            productVariantId = shipmentLineItem.productVariantId,
            quantity = shipmentLineItem.quantity,
            reason = lineResult.reason,
            trackingNumber = shipment.trackingNumber,
          ),
        )
      is LineMatchResult.UserError ->
        return ShipmentMatchResult.UserError(lineResult.messages)
    }
  }

  return ShipmentMatchResult.Ok(fulfillmentOrderGroups, skipped)
}

private sealed interface LineMatchResult {
  data class Matched(
    val fulfillmentOrder: FulfillmentOrder,
    val input: FulfillmentOrderLineItemInput,
  ) : LineMatchResult

  data class Skip(val reason: SkipReason) : LineMatchResult

  data class UserError(val messages: List<String>) : LineMatchResult
}

private data class FulfillmentOrderLineCandidate(
  val fulfillmentOrder: FulfillmentOrder,
  val lineItem: FulfillmentOrderLineItem,
  val availableQuantity: Int,
)

private fun matchShipmentLineItem(
  order: Order,
  shipmentLineItem: ShipmentLineItem,
  ledger: FulfillmentQuantityLedger,
): LineMatchResult {
  val openLines = findOpenFulfillmentOrderLinesForVariant(order, shipmentLineItem.productVariantId)
  if (openLines.isEmpty()) {
    return LineMatchResult.Skip(determineSkipReason(order, shipmentLineItem.productVariantId))
  }

  // Matching always uses post-cancel capacity (totalQuantity).
  if (openLines.all { it.totalQuantity <= 0 }) {
    return LineMatchResult.Skip(SkipReason.ZERO_REMAINING)
  }

  val candidate = findBestFulfillmentOrderLineCandidate(order, shipmentLineItem.productVariantId, ledger)
  if (candidate == null) {
    return LineMatchResult.UserError(
      listOf(
        "variant ${shipmentLineItem.productVariantId} requested quantity ${shipmentLineItem.quantity} exceeds " +
          "remaining 0 on fulfillment order",
      ),
    )
  }

  if (shipmentLineItem.quantity > candidate.availableQuantity) {
    return LineMatchResult.UserError(
      listOf(
        "variant ${shipmentLineItem.productVariantId} requested quantity ${shipmentLineItem.quantity} exceeds " +
          "remaining ${candidate.availableQuantity} on fulfillment order",
      ),
    )
  }

  if (!ledger.tryConsume(candidate.lineItem.id, shipmentLineItem.quantity)) {
    return LineMatchResult.UserError(
      listOf(
        "variant ${shipmentLineItem.productVariantId} requested quantity ${shipmentLineItem.quantity} exceeds " +
          "remaining ${ledger.available(candidate.lineItem.id)} on fulfillment order",
      ),
    )
  }

  return LineMatchResult.Matched(
    fulfillmentOrder = candidate.fulfillmentOrder,
    input = FulfillmentOrderLineItemInput(id = candidate.lineItem.id, quantity = shipmentLineItem.quantity),
  )
}

private fun findOpenFulfillmentOrderLinesForVariant(
  order: Order,
  variantId: Long,
): List<FulfillmentOrderLineItem> {
  val lines = mutableListOf<FulfillmentOrderLineItem>()
  order.fulfillmentOrders.edges.forEach { fulfillmentOrderEdge ->
    val fulfillmentOrder = fulfillmentOrderEdge.node
    if (!fulfillmentOrder.status.isOpenForFulfillment()) return@forEach
    fulfillmentOrder.lineItems.edges.forEach { lineEdge ->
      val node = lineEdge.node
      if (node.variant?.legacyResourceId?.toLongOrNull() == variantId) {
        lines.add(node)
      }
    }
  }
  return lines
}

/**
 * Prefers the open fulfillment-order line with the highest available quantity when the same
 * variant appears in multiple open fulfillment orders. Tie-break: first candidate in Graphql
 * order (strictly greater wins). Availability comes from post-cancel capacity.
 */
private fun findBestFulfillmentOrderLineCandidate(
  order: Order,
  variantId: Long,
  ledger: FulfillmentQuantityLedger,
): FulfillmentOrderLineCandidate? {
  var best: FulfillmentOrderLineCandidate? = null

  for (fulfillmentOrderEdge in order.fulfillmentOrders.edges) {
    val fulfillmentOrder = fulfillmentOrderEdge.node
    if (!fulfillmentOrder.status.isOpenForFulfillment()) continue
    for (lineEdge in fulfillmentOrder.lineItems.edges) {
      val node = lineEdge.node
      val nodeVariantId = node.variant?.legacyResourceId?.toLongOrNull() ?: continue
      if (nodeVariantId != variantId) continue

      val available = ledger.available(node.id)
      if (available <= 0) continue

      val candidate = FulfillmentOrderLineCandidate(fulfillmentOrder, node, available)
      if (best == null || candidate.availableQuantity > best.availableQuantity) {
        best = candidate
      }
    }
  }

  return best
}

private fun determineSkipReason(
  order: Order,
  variantId: Long,
): SkipReason {
  var seenOnOpenFulfillmentOrder = false
  var allZeroCapacity = true

  order.fulfillmentOrders.edges.forEach { fulfillmentOrderEdge ->
    val fulfillmentOrder = fulfillmentOrderEdge.node
    if (!fulfillmentOrder.status.isOpenForFulfillment()) return@forEach
    for (lineEdge in fulfillmentOrder.lineItems.edges) {
      val node = lineEdge.node
      val nodeVariantId = node.variant?.legacyResourceId?.toLongOrNull()
      if (nodeVariantId == null) {
        if (node.variant != null) {
          seenOnOpenFulfillmentOrder = true
        }
        continue
      }
      if (nodeVariantId != variantId) continue
      seenOnOpenFulfillmentOrder = true
      if (node.totalQuantity > 0) {
        allZeroCapacity = false
      }
    }
  }

  return when {
    seenOnOpenFulfillmentOrder && allZeroCapacity -> SkipReason.ZERO_REMAINING
    else -> SkipReason.NO_OPEN_FO
  }
}
