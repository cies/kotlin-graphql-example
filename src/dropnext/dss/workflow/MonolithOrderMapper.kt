package dropnext.dss.workflow

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.OrderLineItem
import dropnext.dss.lib.dss.dto.ShippingAddress
import dropnext.dss.lib.dss.legacyIdFromGid
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.MailingAddress
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Builds [CreateShopifyOrderRequest] from Shopify Admin GraphQL [Order] (hydrated after a webhook).
 * Field names and JSON shape match `openapi.json` / monolith contract (snake_case in wire format).
 */
fun orderToCreateShopifyOrderRequest(
  shopifySubdomain: String,
  order: Order,
): CreateShopifyOrderRequest {
  val orderLegacy = legacyIdFromGid(order.id) ?: order.legacyResourceId.toLong()
  val shipping = order.shippingAddress?.toDto() ?: ShippingAddress(
    firstName = null,
    lastName = null,
    address1 = "",
    address2 = null,
    city = "",
    province = null,
    provinceCode = null,
    countryCode = "",
    zip = null,
    phone = null,
  )
  val lineItems = order.lineItems.edges.mapNotNull { edge ->
    val li = edge.node
    val variant = li.variant ?: return@mapNotNull null
    val variantLegacy = variant.legacyResourceId.toLongOrNull() ?: return@mapNotNull null
    val foId = findFulfillmentOrderLegacyIdForVariant(order, variant) ?: return@mapNotNull null
    val lineId = legacyIdFromGid(li.id) ?: return@mapNotNull null
    val minor = priceToMinorUnits(li.originalUnitPriceSet.shopMoney.amount)
    OrderLineItem(
      shopifyLineItemId = lineId,
      productVariantId = variantLegacy,
      quantity = li.quantity,
      fulfillmentOrderId = foId,
      snapshotOfVariantTitle = li.name,
      snapshotOfProductTitle = li.title,
      snapshotOfPriceInMinorUnits = minor,
    )
  }
  val totalMinor = priceToMinorUnits(order.totalPriceSet.shopMoney.amount)
    .takeIf { it > 0L }
    ?: lineItems.sumOf { it.snapshotOfPriceInMinorUnits * it.quantity.toLong() }.coerceAtLeast(0L)
  val currency = order.totalPriceSet.shopMoney.currencyCode.name
  return CreateShopifyOrderRequest(
    shopifySubdomain = shopifySubdomain,
    shopifyOrderId = orderLegacy,
    name = order.name,
    financialStatus = order.displayFinancialStatus?.toFinancialString() ?: "unknown",
    fulfillmentStatus = order.displayFulfillmentStatus.toMonolithFulfillmentStatus(),
    createdAt = formatCreatedAtUtcZ(order.createdAt),
    shippingAddress = shipping,
    lineItems = lineItems,
    totalInMinorUnits = totalMinor,
    currency = currency,
  )
}

private fun OrderDisplayFinancialStatus.toFinancialString(): String {
  val n = name
  return if (n == "__UNKNOWN_VALUE") "unknown" else n.lowercase(Locale.ROOT)
}

/**
 * Monolith expects REST-style fulfillment: `fulfilled` / `partial` / `restocked`, or **`null`**
 * when nothing is shipped yet (do not send GraphQL labels like `unfulfilled`).
 */
private fun OrderDisplayFulfillmentStatus.toMonolithFulfillmentStatus(): String? =
  when (this) {
    OrderDisplayFulfillmentStatus.FULFILLED -> "fulfilled"

    OrderDisplayFulfillmentStatus.PARTIALLY_FULFILLED,
    OrderDisplayFulfillmentStatus.IN_PROGRESS -> "partial"

    OrderDisplayFulfillmentStatus.RESTOCKED -> "restocked"

    OrderDisplayFulfillmentStatus.UNFULFILLED,
    OrderDisplayFulfillmentStatus.OPEN,
    OrderDisplayFulfillmentStatus.ON_HOLD,
    OrderDisplayFulfillmentStatus.SCHEDULED,
    OrderDisplayFulfillmentStatus.PENDING_FULFILLMENT,
    OrderDisplayFulfillmentStatus.REQUEST_DECLINED,
    OrderDisplayFulfillmentStatus.__UNKNOWN_VALUE -> null
  }

private fun findFulfillmentOrderLegacyIdForVariant(order: Order, variant: ProductVariant): Long? {
  val legacyVariantId = variant.legacyResourceId.toLongOrNull() ?: return null
  for (fulfillmentOrderEdge in order.fulfillmentOrders.edges) {
    val fulfillmentOrder = fulfillmentOrderEdge.node
    for (lineItemEdge in fulfillmentOrder.lineItems.edges) {
      val node = lineItemEdge.node
      val legacyVariantIdFromNode = node.variant?.legacyResourceId?.toLongOrNull()
      if (legacyVariantIdFromNode == legacyVariantId) {
        return legacyIdFromGid(fulfillmentOrder.id)
      }
    }
  }
  return null
}

private fun MailingAddress.toDto(): ShippingAddress =
  ShippingAddress(
    firstName = firstName,
    lastName = lastName,
    address1 = address1.orEmpty(),
    address2 = address2,
    city = city.orEmpty(),
    province = province,
    provinceCode = provinceCode,
    countryCode = countryCodeV2?.name.orEmpty(),
    zip = zip,
    phone = phone,
  )

// TODO: This assumes a 100-based minor unit currency; not all currencies are like that.
private fun priceToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}

/** Normalizes Shopify Admin `DateTime` strings to UTC `…Z` (second precision), e.g. `2026-04-25T10:30:00Z`. */
private fun formatCreatedAtUtcZ(createdAt: String): String =
  try {
    val instant = OffsetDateTime.parse(createdAt).toInstant().truncatedTo(ChronoUnit.SECONDS)
    DateTimeFormatter.ISO_INSTANT.format(instant)
  } catch (_: Exception) {
    createdAt
  }
