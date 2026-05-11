package dropnext.dss.lib.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShippingAddress(
  @SerialName("first_name") val firstName: String? = null,
  @SerialName("last_name") val lastName: String? = null,
  @SerialName("address1") val address1: String,
  @SerialName("address2") val address2: String? = null,
  @SerialName("city") val city: String,
  @SerialName("province") val province: String? = null,
  @SerialName("province_code") val provinceCode: String? = null,
  @SerialName("country_code") val countryCode: String,
  @SerialName("zip") val zip: String? = null,
  @SerialName("phone") val phone: String? = null,
)

@Serializable
data class OrderLineItem(
  @SerialName("shopify_line_item_id") val shopifyLineItemId: Long,
  @SerialName("product_variant_id") val productVariantId: Long,
  @SerialName("quantity") val quantity: Int,
  @SerialName("fulfillment_order_id") val fulfillmentOrderId: Long,
  @SerialName("snapshot_of_variant_title") val snapshotOfVariantTitle: String,
  @SerialName("snapshot_of_product_title") val snapshotOfProductTitle: String,
  @SerialName("snapshot_of_price_in_minor_units") val snapshotOfPriceInMinorUnits: Long,
)

@Serializable
data class CreateShopifyOrderRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("shopify_order_id") val shopifyOrderId: Long,
  @SerialName("name") val name: String,
  @SerialName("financial_status") val financialStatus: String,
  @SerialName("fulfillment_status") val fulfillmentStatus: String? = null,
  @SerialName("created_at") val createdAt: String,
  @SerialName("shipping_address") val shippingAddress: ShippingAddress,
  @SerialName("line_items") val lineItems: List<OrderLineItem>,
  @SerialName("total_in_minor_units") val totalInMinorUnits: Long,
  @SerialName("currency") val currency: String,
)

@Serializable
data class CreateOrderResponse(
  @SerialName("shopify_order_id") val shopifyOrderId: Long,
)
