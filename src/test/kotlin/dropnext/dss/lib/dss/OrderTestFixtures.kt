package dropnext.dss.lib.dss

import com.example.graphql.generated.enums.CurrencyCode
import com.example.graphql.generated.enums.OrderDisplayFinancialStatus
import com.example.graphql.generated.enums.OrderDisplayFulfillmentStatus
import com.example.graphql.generated.getorderfordss.FulfillmentOrderConnection
import com.example.graphql.generated.getorderfordss.LineItem
import com.example.graphql.generated.getorderfordss.LineItemConnection
import com.example.graphql.generated.getorderfordss.LineItemEdge
import com.example.graphql.generated.getorderfordss.MoneyBag
import com.example.graphql.generated.getorderfordss.MoneyBag2
import com.example.graphql.generated.getorderfordss.MoneyV2
import com.example.graphql.generated.getorderfordss.MoneyV22
import com.example.graphql.generated.getorderfordss.Order
import com.example.graphql.generated.getorderfordss.ProductVariant

internal fun minimalOrder(
  fulfillment: OrderDisplayFulfillmentStatus = OrderDisplayFulfillmentStatus.UNFULFILLED,
  financial: OrderDisplayFinancialStatus = OrderDisplayFinancialStatus.PAID,
): Order {
  val variant =
    ProductVariant(
      id = "gid://shopify/ProductVariant/101",
      legacyResourceId = "101",
    )
  val lineItem =
    LineItem(
      id = "gid://shopify/LineItem/201",
      quantity = 2,
      name = "T-Shirt - Blue",
      title = "T-Shirt",
      originalUnitPriceSet = MoneyBag2(shopMoney = MoneyV22(amount = "19.99")),
      variant = variant,
    )
  return Order(
    id = "gid://shopify/Order/1001",
    name = "#1001",
    legacyResourceId = "1001",
    createdAt = "2026-04-25T10:30:00+00:00",
    totalPriceSet =
      MoneyBag(
        shopMoney = MoneyV2(amount = "39.98", currencyCode = CurrencyCode.USD),
      ),
    displayFinancialStatus = financial,
    displayFulfillmentStatus = fulfillment,
    shippingAddress = null,
    lineItems = LineItemConnection(edges = listOf(LineItemEdge(node = lineItem))),
    fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList()),
    fulfillments = emptyList(),
  )
}
