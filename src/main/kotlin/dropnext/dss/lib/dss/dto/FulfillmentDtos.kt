package dropnext.dss.lib.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShipmentLineItem(
  @SerialName("product_variant_id") val productVariantId: Long,
  @SerialName("quantity") val quantity: Int,
)

@Serializable
data class Shipment(
  @SerialName("tracking_number") val trackingNumber: String,
  @SerialName("carrier") val carrier: String? = null,
  @SerialName("tracking_url") val trackingUrl: String? = null,
  @SerialName("line_items") val lineItems: List<ShipmentLineItem>,
)

@Serializable
data class SyncShipmentsWithFulfillmentsRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("shopify_order_id") val shopifyOrderId: Long,
  @SerialName("shipments") val shipments: List<Shipment>,
)

@Serializable
data class SyncShipmentsWithFulfillmentsResponse(
  @SerialName("new_fulfillment_ids") val newFulfillmentIds: List<Long>,
)

@Serializable
data class TrackingUpdateRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("shopify_order_id") val shopifyOrderId: Long,
  @SerialName("tracking_number") val trackingNumber: String,
  @SerialName("status") val status: String,
  @SerialName("happened_at") val happenedAt: String,
  @SerialName("message") val message: String? = null,
)

@Serializable
data class TrackingUpdateResponse(
  @SerialName("fulfillment_event_id") val fulfillmentEventId: Long,
)
