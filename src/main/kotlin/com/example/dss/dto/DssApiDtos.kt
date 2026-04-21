package com.example.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Simple `{ "error": "..." }` body when something goes wrong. */
@Serializable data class ErrorResponse(val error: String)

/** What we return when you look up a store — internal id, Shopify’s shop id, and the saved token field. */
@Serializable
data class StoreResponse(
  val store_id: Long,
  val shopify_shop_id: Long,
  val api_key: String?,
)

/** Body for “save this shop’s Shopify token” (`PUT /stores/api-key`). */
@Serializable
data class UpdateStoreApiKeyRequest(
  val shopify_subdomain: String,
  val shopify_shop_id: Long,
  val api_key: String,
)

@Serializable data class UpdateStoreApiKeyResponse(val store_id: Long)

@Serializable
data class VariantIdsResponse(val product_variant_ids: List<Long>)

@Serializable
data class UpsertProductVariantsRequest(
  val shopify_subdomain: String,
  val product_variants: List<ProductVariantItem>,
)

@Serializable data class UpsertProductVariantsResponse(val upserted: Int)

@Serializable
data class DeleteProductVariantsRequest(
  val shopify_subdomain: String,
  val product_variant_ids: List<Long>,
)

@Serializable data class DeleteProductVariantsResponse(val deleted: Int)

@Serializable
enum class ProductStatus {
  active,
  draft,
  archived,
}

@Serializable
data class SelectedOption(
  val name: String,
  val value: String,
)

@Serializable
data class ProductVariantItem(
  val product_variant_id: Long,
  val product_id: Long,
  val product_title: String,
  val product_description: String,
  val product_description_html: String,
  val product_vendor: String,
  val product_type: String,
  val product_tags: List<String>,
  val product_handle: String,
  val product_status: ProductStatus,
  val product_images: List<String>,
  val product_published_at: String? = null,
  val product_created_at: String,
  val product_updated_at: String,
  val title: String,
  val sku: String? = null,
  val barcode: String? = null,
  val price_in_minor_units: Long,
  val price_currency: String,
  val selected_options: List<SelectedOption>,
  val image_url: String? = null,
)

@Serializable
data class ShippingAddress(
  val first_name: String? = null,
  val last_name: String? = null,
  val address1: String,
  val address2: String? = null,
  val city: String,
  val province: String? = null,
  val province_code: String? = null,
  val country_code: String,
  val zip: String? = null,
  val phone: String? = null,
)

@Serializable
data class OrderLineItem(
  val order_line_item_id: Long,
  val product_variant_id: Long,
  val quantity: Int,
  val fulfillment_order_id: Long,
  val snapshot_of_variant_title: String,
  val snapshot_of_product_title: String,
  val snapshot_of_price_in_minor_units: Long,
)

/**
 * JSON we POST to the monolith when Shopify tells us a new order was created.
 * Field names use snake_case on purpose — that’s what the OpenAPI contract uses.
 */
@Serializable
data class CreateShopifyOrderRequest(
  val shopify_subdomain: String,
  val shopify_order_id: Long,
  val name: String,
  val financial_status: String,
  /** Use `""` if Shopify didn’t give us a clear fulfillment status. */
  val fulfillment_status: String,
  val created_at: String,
  val shipping_address: ShippingAddress,
  val line_items: List<OrderLineItem>,
)

@Serializable data class CreateOrderResponse(val shopify_order_id: Long)

@Serializable
data class ShipmentLineItem(
  val product_variant_id: Long,
  val quantity: Int,
)

/**
 * One shipment line in the “sync fulfillments” call (OpenAPI calls this shape `Fulfillment`).
 * Carrier and URL can be null, but the JSON usually still includes those keys.
 */
@Serializable
data class FulfillmentPayload(
  val fulfillment_order_id: Long,
  val tracking_number: String,
  val carrier: String? = null,
  val tracking_url: String? = null,
  val line_items: List<ShipmentLineItem>,
)

/** Full body for `POST /sync-shipments-with-fulfillments` (and `/dummy2`). */
@Serializable
data class SyncShipmentsWithFulfillmentsPayload(
  val shopify_subdomain: String,
  val shopify_order_id: Long,
  val replace_fulfillment_ids: List<Long>,
  val new_fulfillments: List<FulfillmentPayload>,
)

@Serializable
data class SyncShipmentsWithFulfillmentsResponse(
  val new_fulfillment_ids: List<Long>,
)

/** Body for tracking webhooks — “this package moved” / status changed. */
@Serializable
data class TrackingUpdatePayload(
  val shopify_subdomain: String,
  @SerialName("fulfillment_id") val fulfillment_id: Long,
  val status: String,
  val happened_at: String,
  /** Extra text for logs; can be null. */
  val message: String? = null,
)

@Serializable
data class TrackingUpdateResponse(
  val fulfillment_event_id: Long,
)
