package dropnext.dss.lib.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(@SerialName(value = "error") val error: String)

@Serializable
data class ShippingAddress(
  @SerialName(value = "first_name") val firstName: String? = null,
  @SerialName(value = "last_name") val lastName: String? = null,
  @SerialName(value = "address1") val address1: String,
  @SerialName(value = "address2") val address2: String? = null,
  @SerialName(value = "city") val city: String,
  @SerialName(value = "province") val province: String? = null,
  @SerialName(value = "province_code") val provinceCode: String? = null,
  @SerialName(value = "country_code") val countryCode: String,
  @SerialName(value = "zip") val zip: String? = null,
  @SerialName(value = "phone") val phone: String? = null,
)

@Serializable
data class OrderLineItem(
  @SerialName(value = "order_line_item_id") val orderLineItemId: Long,
  @SerialName(value = "product_variant_id") val productVariantId: Long,
  @SerialName(value = "quantity") val quantity: Int,
  @SerialName(value = "fulfillment_order_id") val fulfillmentOrderId: Long,
  @SerialName(value = "snapshot_of_variant_title") val snapshotOfVariantTitle: String,
  @SerialName(value = "snapshot_of_product_title") val snapshotOfProductTitle: String,
  @SerialName(value = "snapshot_of_price_in_minor_units") val snapshotOfPriceInMinorUnits: Long,
)

@Serializable
data class CreateShopifyOrderRequest(
  @SerialName(value = "shopify_subdomain") val shopifySubdomain: String,
  @SerialName(value = "shopify_order_id") val shopifyOrderId: Long,
  @SerialName(value = "name") val name: String,
  @SerialName(value = "financial_status") val financialStatus: String,
  /** Use `""` if Shopify did not give us a clear fulfillment status. */
  @SerialName(value = "fulfillment_status") val fulfillmentStatus: String,
  @SerialName(value = "created_at") val createdAt: String,
  @SerialName(value = "shipping_address") val shippingAddress: ShippingAddress,
  @SerialName(value = "line_items") val lineItems: List<OrderLineItem>,
  /** Order total in minor units (non-negative). */
  @SerialName(value = "total_in_minor_units") val totalInMinorUnits: Long,
)

@Serializable
data class CreateOrderResponse(
  @SerialName(value = "shopify_order_id") val shopifyOrderId: Long,
)

@Serializable
data class ShipmentLineItem(
  @SerialName(value = "product_variant_id") val productVariantId: Long,
  @SerialName(value = "quantity") val quantity: Int,
)

@Serializable
data class FulfillmentPayload(
  @SerialName(value = "fulfillment_order_id") val fulfillmentOrderId: Long,
  @SerialName(value = "tracking_number") val trackingNumber: String,
  @SerialName(value = "carrier") val carrier: String? = null,
  @SerialName(value = "tracking_url") val trackingUrl: String? = null,
  @SerialName(value = "line_items") val lineItems: List<ShipmentLineItem>,
)

@Serializable
data class SyncShipmentsWithFulfillmentsPayload(
  @SerialName(value = "shopify_subdomain") val shopifySubdomain: String,
  @SerialName(value = "shopify_order_id") val shopifyOrderId: Long,
  @SerialName(value = "replace_fulfillment_ids") val replaceFulfillmentIds: List<Long>,
  @SerialName(value = "new_fulfillments") val newFulfillments: List<FulfillmentPayload>,
)

@Serializable
data class SyncShipmentsWithFulfillmentsResponse(
  @SerialName(value = "new_fulfillment_ids") val newFulfillmentIds: List<Long>,
)

@Serializable
data class TrackingUpdatePayload(
  @SerialName(value = "shopify_subdomain") val shopifySubdomain: String,
  @SerialName(value = "fulfillment_id") val fulfillmentId: Long,
  @SerialName(value = "status") val status: String,
  @SerialName(value = "happened_at") val happenedAt: String,
  @SerialName(value = "message") val message: String? = null,
)

@Serializable
data class TrackingUpdateResponse(
  @SerialName(value = "fulfillment_event_id") val fulfillmentEventId: Long,
)
