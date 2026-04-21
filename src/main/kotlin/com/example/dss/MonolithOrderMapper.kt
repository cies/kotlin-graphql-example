package com.example.dss

import com.example.dss.dto.CreateShopifyOrderRequest
import com.example.dss.dto.OrderLineItem
import com.example.dss.dto.ShippingAddress
import com.example.graphql.generated.getorderfordss.MailingAddress
import com.example.graphql.generated.enums.OrderDisplayFinancialStatus
import com.example.graphql.generated.enums.OrderDisplayFulfillmentStatus
import com.example.graphql.generated.getorderfordss.Order
import com.example.graphql.generated.getorderfordss.ProductVariant
import kotlin.math.roundToLong

fun orderToCreateShopifyOrderRequest(
  shopifySubdomain: String,
  order: Order,
): CreateShopifyOrderRequest {
  val orderLegacy = legacyIdFromGid(order.id.toString()) ?: order.legacyResourceId.toString().toLong()
  val shipping =
    order.shippingAddress?.toDto()
      ?: ShippingAddress(address1 = "", city = "", country_code = "")
  val lineItems =
    order.lineItems.edges.mapNotNull { edge ->
      val li = edge.node
      val variant = li.variant ?: return@mapNotNull null
      val variantLegacy = variant.legacyResourceId.toString().toLongOrNull() ?: return@mapNotNull null
      val foId = findFulfillmentOrderLegacyIdForVariant(order, variant) ?: return@mapNotNull null
      val lineId = legacyIdFromGid(li.id.toString()) ?: return@mapNotNull null
      val minor = priceToMinorUnits(li.originalUnitPriceSet.shopMoney.amount.toString())
      OrderLineItem(
        order_line_item_id = lineId,
        product_variant_id = variantLegacy,
        quantity = li.quantity,
        fulfillment_order_id = foId,
        snapshot_of_variant_title = li.name,
        snapshot_of_product_title = li.title,
        snapshot_of_price_in_minor_units = minor,
      )
    }
  return CreateShopifyOrderRequest(
    shopify_subdomain = shopifySubdomain,
    shopify_order_id = orderLegacy,
    name = order.name,
    financial_status = order.displayFinancialStatus?.toFinancialString() ?: "unknown",
    fulfillment_status = order.displayFulfillmentStatus.toFulfillmentString(),
    created_at = order.createdAt,
    shipping_address = shipping,
    line_items = lineItems,
  )
}

private fun OrderDisplayFinancialStatus.toFinancialString(): String =
  name.takeIf { it != "__UNKNOWN_VALUE" } ?: "unknown"

private fun OrderDisplayFulfillmentStatus.toFulfillmentString(): String =
  name.takeIf { it != "__UNKNOWN_VALUE" } ?: ""

private fun findFulfillmentOrderLegacyIdForVariant(order: Order, variant: ProductVariant): Long? {
  val v = variant.legacyResourceId.toString().toLongOrNull() ?: return null
  for (foe in order.fulfillmentOrders.edges) {
    val fo = foe.node
    for (lie in fo.lineItems.edges) {
      val node = lie.node
      val nv = node.variant?.legacyResourceId?.toString()?.toLongOrNull()
      if (nv == v) {
        return legacyIdFromGid(fo.id.toString())
      }
    }
  }
  return null
}

private fun MailingAddress.toDto(): ShippingAddress =
  ShippingAddress(
    first_name = firstName,
    last_name = lastName,
    address1 = address1 ?: "",
    address2 = address2,
    city = city ?: "",
    province = province,
    province_code = provinceCode,
    country_code = countryCodeV2?.name ?: "",
    zip = zip,
    phone = phone,
  )

private fun priceToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}
