package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.TrackingUpdateRequest


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

fun SyncShipmentsWithFulfillmentsRequest.validate(): RequestValidation {
  val errors = mutableListOf<String>()
  errors += validateOrderId(this.shopifyOrderId)
  if (this.shipments.isEmpty()) {
    errors += "at least one shipment is required"
  } else {
    this.shipments.forEachIndexed { index, shipment ->
      errors += validateShipment(shipment, index)
    }
    errors += validateDuplicateTrackingNumbers(this.shipments)
  }
  return errors.toResult()
}

private fun validateDuplicateTrackingNumbers(shipments: List<Shipment>): List<String> {
  val seen = mutableSetOf<String>()
  val duplicates = linkedSetOf<String>()
  for (shipment in shipments) {
    val tracking = shipment.trackingNumber.trim()
    if (!seen.add(tracking)) {
      duplicates.add(tracking)
    }
  }
  return if (duplicates.isEmpty()) {
    emptyList()
  } else {
    listOf("duplicate tracking_number in payload: ${duplicates.joinToString(", ")}")
  }
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
