package dropnext.dss.lib.fulfillment

import dropnext.dss.lib.dss.dto.Shipment
import dropnext.dss.lib.dss.dto.ShipmentLineItem
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest

sealed interface RequestValidation {
  data object Valid : RequestValidation

  data class Invalid(val message: String) : RequestValidation
}

fun validateSyncShipmentsRequest(request: SyncShipmentsWithFulfillmentsRequest): RequestValidation {
  validateOrderId(request.shopifyOrderId)?.let { return it }
  if (request.shipments.isEmpty()) {
    return RequestValidation.Invalid("at least one shipment is required")
  }
  for (shipment in request.shipments) {
    validateShipment(shipment)?.let { return it }
  }
  return RequestValidation.Valid
}

fun validateTrackingUpdateRequest(request: TrackingUpdateRequest): RequestValidation {
  validateOrderId(request.shopifyOrderId)?.let { return it }
  if (request.trackingNumber.isBlank()) {
    return RequestValidation.Invalid("tracking_number is required")
  }
  if (request.status.isBlank()) {
    return RequestValidation.Invalid("status is required")
  }
  return RequestValidation.Valid
}

private fun validateOrderId(shopifyOrderId: Long): RequestValidation.Invalid? =
  if (shopifyOrderId <= 0L) {
    RequestValidation.Invalid("invalid shopify_order_id: must be positive")
  } else {
    null
  }

private fun validateShipment(shipment: Shipment): RequestValidation.Invalid? {
  if (shipment.trackingNumber.isBlank()) {
    return RequestValidation.Invalid("shipment tracking_number is required")
  }
  if (shipment.carrier.isNullOrBlank()) {
    return RequestValidation.Invalid("shipment carrier is required")
  }
  if (shipment.lineItems.isEmpty()) {
    return RequestValidation.Invalid("shipment line_items must not be empty")
  }
  for (line in shipment.lineItems) {
    validateShipmentLineItem(line)?.let { return it }
  }
  return null
}

private fun validateShipmentLineItem(line: ShipmentLineItem): RequestValidation.Invalid? =
  if (line.quantity <= 0) {
    RequestValidation.Invalid("line item quantity must be positive (variant_id=${line.productVariantId})")
  } else {
    null
  }
