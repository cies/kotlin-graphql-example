package dropnext.dss.lib.dss

import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.LineItem
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import dropnext.graphql.generated.getorderfordss.MoneyBag
import dropnext.graphql.generated.getorderfordss.MoneyBag2
import dropnext.graphql.generated.getorderfordss.MoneyV2
import dropnext.graphql.generated.getorderfordss.MoneyV22
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant

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
    fulfillmentOrders =
      FulfillmentOrderConnection(
        edges =
          listOf(
            FulfillmentOrderEdge(
              node =
                FulfillmentOrder(
                  id = "gid://shopify/FulfillmentOrder/301",
                  status = FulfillmentOrderStatus.OPEN,
                  lineItems =
                    FulfillmentOrderLineItemConnection(
                      edges =
                        listOf(
                          FulfillmentOrderLineItemEdge(
                            node =
                              FulfillmentOrderLineItem(
                                id = "gid://shopify/FulfillmentOrderLineItem/401",
                                remainingQuantity = 2,
                                totalQuantity = 2,
                                variant = variant,
                              ),
                          ),
                        ),
                    ),
                ),
            ),
          ),
      ),
    fulfillments = emptyList(),
  )
}
