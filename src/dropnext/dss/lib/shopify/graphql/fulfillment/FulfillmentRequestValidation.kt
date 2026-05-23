package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.TrackingUpdateRequest

sealed interface RequestValidation {
  data object Valid : RequestValidation

  data class Invalid(val messages: List<String>) : RequestValidation {
    init { require(messages.isNotEmpty()) }

    /** All messages joined for use in a single error body. */
    val message: String get() = messages.joinToString("; ")
  }
}

private fun List<String>.toResult(): RequestValidation =
  if (isEmpty()) RequestValidation.Valid else RequestValidation.Invalid(this)

fun validateSyncShipmentsRequest(request: SyncShipmentsWithFulfillmentsRequest): RequestValidation {
  val errors = mutableListOf<String>()
  errors += validateOrderId(request.shopifyOrderId)
  if (request.shipments.isEmpty()) {
    errors += "at least one shipment is required"
  } else {
    request.shipments.forEachIndexed { index, shipment ->
      errors += validateShipment(shipment, index)
    }
  }
  return errors.toResult()
}

fun validateTrackingUpdateRequest(request: TrackingUpdateRequest): RequestValidation {
  val errors = mutableListOf<String>()
  errors += validateOrderId(request.shopifyOrderId)
  if (request.trackingNumber.isBlank()) errors += "tracking_number is required"
  if (request.status.isBlank()) errors += "status is required"
  return errors.toResult()
}

private fun validateOrderId(shopifyOrderId: Long): List<String> =
  if (shopifyOrderId <= 0L) listOf("invalid shopify_order_id: must be positive") else emptyList()

private fun validateShipment(shipment: Shipment, index: Int): List<String> {
  val errors = mutableListOf<String>()
  val prefix = "shipments[$index]"
  if (shipment.trackingNumber.isBlank()) errors += "$prefix tracking_number is required"
  if (shipment.carrier.isNullOrBlank()) errors += "$prefix carrier is required"
  if (shipment.lineItems.isEmpty()) {
    errors += "$prefix line_items must not be empty"
  } else {
    shipment.lineItems.forEach { line -> errors += validateShipmentLineItem(line) }
  }
  return errors
}

private fun validateShipmentLineItem(line: ShipmentLineItem): List<String> =
  if (line.quantity <= 0) {
    listOf("line item quantity must be positive (variant_id=${line.productVariantId})")
  } else {
    emptyList()
  }
