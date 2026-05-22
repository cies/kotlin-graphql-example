package dropnext.dss.workflow

import dropnext.graphql.generated.enums.CountryCode
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.MailingAddress
import kotlin.test.Test

class MonolithOrderMapperTest {

  @Test
  fun `maps financial status to lowercase`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder(financial = OrderDisplayFinancialStatus.PAID))
    assert(req.financialStatus == "paid")
  }

  @Test
  fun `maps unfulfilled to null fulfillment_status`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.UNFULFILLED),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps fulfilled to fulfilled string`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.FULFILLED),
      )
    assert(req.fulfillmentStatus == "fulfilled")
  }

  @Test
  fun `normalizes created_at to UTC Z`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder())
    assert(req.createdAt == "2026-04-25T10:30:00Z")
  }

  @Test
  fun `omits line items without resolvable fulfillment_order_id`() {
    val order = minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList()))
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    assert(req.lineItems.isEmpty())
  }

  @Test
  fun `maps PARTIALLY_FULFILLED to partial`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.PARTIALLY_FULFILLED),
      )
    assert(req.fulfillmentStatus == "partial")
  }

  @Test
  fun `maps IN_PROGRESS to partial`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.IN_PROGRESS),
      )
    assert(req.fulfillmentStatus == "partial")
  }

  @Test
  fun `maps RESTOCKED to restocked`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.RESTOCKED),
      )
    assert(req.fulfillmentStatus == "restocked")
  }

  @Test
  fun `maps ON_HOLD to null`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.ON_HOLD),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps unknown fulfillment status to null`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.__UNKNOWN_VALUE),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps unknown financial status to unknown string`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.__UNKNOWN_VALUE),
      )
    assert(req.financialStatus == "unknown")
  }

  @Test
  fun `maps REFUNDED financial status to refunded`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.REFUNDED),
      )
    assert(req.financialStatus == "refunded")
  }

  @Test
  fun `maps PARTIALLY_PAID financial status to partially_paid`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.PARTIALLY_PAID),
      )
    assert(req.financialStatus == "partially_paid")
  }

  @Test
  fun `omits line items whose variant is null`() {
    val base = minimalOrder()
    val withNullVariant = base.copy(
      lineItems = dropnext.graphql.generated.getorderfordss.LineItemConnection(
        edges = listOf(
          dropnext.graphql.generated.getorderfordss.LineItemEdge(
            node = base.lineItems.edges.single().node.copy(variant = null),
          ),
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", withNullVariant)
    assert(req.lineItems.isEmpty())
  }

  @Test
  fun `maps shippingAddress when present`() {
    val mailing = MailingAddress(
      firstName = "Ada",
      lastName = "Lovelace",
      address1 = "10 Downing St",
      address2 = "Apt 2",
      city = "London",
      province = "England",
      provinceCode = "ENG",
      countryCodeV2 = CountryCode.GB,
      zip = "SW1A 2AA",
      phone = "+44 20 7946 0958",
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = mailing))
    val s = req.shippingAddress
    assert(s.firstName == "Ada")
    assert(s.lastName == "Lovelace")
    assert(s.address1 == "10 Downing St")
    assert(s.address2 == "Apt 2")
    assert(s.city == "London")
    assert(s.province == "England")
    assert(s.provinceCode == "ENG")
    assert(s.countryCode == "GB")
    assert(s.zip == "SW1A 2AA")
    assert(s.phone == "+44 20 7946 0958")
  }

  @Test
  fun `shippingAddress null mailing fields are coerced to empty strings on non-null DTO fields`() {
    val mailing = MailingAddress(
      address1 = null,
      city = null,
      countryCodeV2 = null,
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = mailing))
    assert(req.shippingAddress.address1 == "")
    assert(req.shippingAddress.city == "")
    assert(req.shippingAddress.countryCode == "")
  }

  @Test
  fun `default shipping address is used when order has none`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = null))
    assert(req.shippingAddress.address1 == "")
    assert(req.shippingAddress.firstName == null)
  }

  @Test
  fun `falls back to lineItems sum when totalPriceSet is non-positive`() {
    val order = minimalOrder().copy(
      totalPriceSet = dropnext.graphql.generated.getorderfordss.MoneyBag(
        shopMoney = dropnext.graphql.generated.getorderfordss.MoneyV2(
          amount = "0.00",
          currencyCode = dropnext.graphql.generated.enums.CurrencyCode.USD,
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    val lineSum = req.lineItems.sumOf { it.snapshotOfPriceInMinorUnits * it.quantity.toLong() }
    assert(req.totalInMinorUnits == lineSum)
  }

  @Test
  fun `falls back to lineItems sum when totalPriceSet is unparseable`() {
    val order = minimalOrder().copy(
      totalPriceSet = dropnext.graphql.generated.getorderfordss.MoneyBag(
        shopMoney = dropnext.graphql.generated.getorderfordss.MoneyV2(
          amount = "n/a",
          currencyCode = dropnext.graphql.generated.enums.CurrencyCode.USD,
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    val lineSum = req.lineItems.sumOf { it.snapshotOfPriceInMinorUnits * it.quantity.toLong() }
    assert(req.totalInMinorUnits == lineSum)
  }

  @Test
  fun `falls back to raw createdAt when parsing fails`() {
    val order = minimalOrder().copy(createdAt = "not-a-date")
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    assert(req.createdAt == "not-a-date")
  }
}
