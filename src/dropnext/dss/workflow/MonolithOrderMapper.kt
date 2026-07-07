package dropnext.dss.workflow

import dropnext.dss.lib.monolith.dto.generated.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.dto.generated.OrderLineItem
import dropnext.dss.lib.monolith.dto.generated.ShippingAddress
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.shopify.minorUnitsToShopifyDecimal
import dropnext.dss.shopify.shopifyDecimalToMinorUnits
import dropnext.dss.shopify.shopifyMoneyAmountForWire
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.MailingAddress
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Builds [CreateShopifyOrderRequest] from Shopify Admin Graphql [Order] (hydrated after a webhook).
 * Field names and JSON shape match `openapi.json` / monolith contract (snake_case in wire format).
 */
fun orderToCreateShopifyOrderRequest(
  shopifySubdomain: String,
  order: Order,
): CreateShopifyOrderRequest {
  val orderLegacy = legacyIdFromGid(order.id) ?: order.legacyResourceId.toLongOrNull() ?: 0L
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
    OrderLineItem(
      shopifyLineItemId = lineId,
      productVariantId = variantLegacy,
      quantity = li.quantity,
      fulfillmentOrderId = foId,
      snapshotOfVariantTitle = li.name,
      snapshotOfProductTitle = li.title,
      snapshotOfPriceAsString = shopifyMoneyAmountForWire(li.originalUnitPriceSet.shopMoney.amount),
    )
  }
  val totalString = resolveOrderTotalAsString(
    orderTotalAmount = order.totalPriceSet.shopMoney.amount,
    lineItems = lineItems,
  )
  val currency = order.totalPriceSet.shopMoney.currencyCode.name
  return CreateShopifyOrderRequest(
    shopifySubdomain = shopifySubdomain,
    shopifyOrderId = orderLegacy,
    name = order.name,
    financialStatus = order.displayFinancialStatus?.toFinancialString() ?: "Unknown",
    fulfillmentStatus = order.displayFulfillmentStatus.toMonolithFulfillmentStatus(),
    createdAt = formatCreatedAtUtcZ(order.createdAt),
    shippingAddress = shipping,
    lineItems = lineItems,
    totalAsString = totalString,
    currency = currency,
  )
}

/**
 * Uses Shopify's order total when it parses to a positive amount; otherwise derives the total
 * from line-item unit prices × quantities (same fallback as the previous minor-units contract).
 */
internal fun resolveOrderTotalAsString(
  orderTotalAmount: String?,
  lineItems: List<OrderLineItem>,
): String {
  val normalizedOrderTotal = shopifyMoneyAmountForWire(orderTotalAmount)
  if (shopifyDecimalToMinorUnits(normalizedOrderTotal) > 0L) {
    return normalizedOrderTotal
  }
  val lineSumMinor = lineItems.sumOf { item ->
    shopifyDecimalToMinorUnits(item.snapshotOfPriceAsString) * item.quantity.toLong()
  }.coerceAtLeast(0L)
  return minorUnitsToShopifyDecimal(lineSumMinor)
}

private fun OrderDisplayFinancialStatus.toFinancialString(): String =
  if (name == "__UNKNOWN_VALUE") "Unknown" else enumNameToPascalCase(name)

/**
 * Monolith expects PascalCase fulfillment labels (`Fulfilled` / `Partial` / `Restocked`), or **`null`**
 * when nothing is shipped yet (do not send Graphql labels like `Unfulfilled`).
 */
private fun OrderDisplayFulfillmentStatus.toMonolithFulfillmentStatus(): String? =
  when (this) {
    OrderDisplayFulfillmentStatus.FULFILLED -> "Fulfilled"

    OrderDisplayFulfillmentStatus.PARTIALLY_FULFILLED,
    OrderDisplayFulfillmentStatus.IN_PROGRESS -> "Partial"

    OrderDisplayFulfillmentStatus.RESTOCKED -> "Restocked"

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

/** Converts Graphql enum names (`PARTIALLY_PAID`) to monolith PascalCase (`PartiallyPaid`). */
private fun enumNameToPascalCase(enumName: String): String =
  enumName.split('_').joinToString("") { part ->
    part.lowercase(Locale.ROOT).replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
  }

/** Normalizes Shopify Admin `DateTime` strings to UTC `…Z` (second precision), e.g. `2026-04-25T10:30:00Z`. */
private fun formatCreatedAtUtcZ(createdAt: String): String =
  try {
    val instant = OffsetDateTime.parse(createdAt).toInstant().truncatedTo(ChronoUnit.SECONDS)
    DateTimeFormatter.ISO_INSTANT.format(instant)
  } catch (_: Exception) {
    createdAt
  }
