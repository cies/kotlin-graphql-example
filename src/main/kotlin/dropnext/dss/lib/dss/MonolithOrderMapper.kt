package dropnext.dss.lib.dss

import com.example.lib.dss.dto.CreateShopifyOrderRequest
import com.example.lib.dss.dto.OrderLineItem
import com.example.lib.dss.dto.ShippingAddress
import com.example.graphql.generated.enums.OrderDisplayFinancialStatus
import com.example.graphql.generated.enums.OrderDisplayFulfillmentStatus
import com.example.graphql.generated.getorderfordss.MailingAddress
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
      ?: ShippingAddress(address1 = "", city = "", countryCode = "")
  val lineItems =
    order.lineItems.edges.mapNotNull { edge ->
      val li = edge.node
      val variant = li.variant ?: return@mapNotNull null
      val variantLegacy = variant.legacyResourceId.toString().toLongOrNull() ?: return@mapNotNull null
      val foId = findFulfillmentOrderLegacyIdForVariant(order, variant) ?: return@mapNotNull null
      val lineId = legacyIdFromGid(li.id.toString()) ?: return@mapNotNull null
      val minor = priceToMinorUnits(li.originalUnitPriceSet.shopMoney.amount.toString())
      OrderLineItem(
        orderLineItemId = lineId,
        productVariantId = variantLegacy,
        quantity = li.quantity,
        fulfillmentOrderId = foId,
        snapshotOfVariantTitle = li.name,
        snapshotOfProductTitle = li.title,
        snapshotOfPriceInMinorUnits = minor,
      )
    }
  val totalMinor =
    order.totalPriceSet?.shopMoney?.amount?.toString()?.let { priceToMinorUnits(it) }
      ?: lineItems.sumOf { it.snapshotOfPriceInMinorUnits * it.quantity.toLong() }.coerceAtLeast(0L)
  return CreateShopifyOrderRequest(
    shopifySubdomain = shopifySubdomain,
    shopifyOrderId = orderLegacy,
    name = order.name,
    financialStatus = order.displayFinancialStatus?.toFinancialString() ?: "unknown",
    fulfillmentStatus = order.displayFulfillmentStatus.toFulfillmentString(),
    createdAt = order.createdAt,
    shippingAddress = shipping,
    lineItems = lineItems,
    totalInMinorUnits = totalMinor,
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
    firstName = firstName,
    lastName = lastName,
    address1 = address1 ?: "",
    address2 = address2,
    city = city ?: "",
    province = province,
    provinceCode = provinceCode,
    countryCode = countryCodeV2?.name ?: "",
    zip = zip,
    phone = phone,
  )

private fun priceToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}
