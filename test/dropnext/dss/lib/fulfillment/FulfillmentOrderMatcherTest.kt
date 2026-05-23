package dropnext.dss.lib.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.shopify.graphql.fulfillment.ShipmentMatchResult
import dropnext.dss.lib.shopify.graphql.fulfillment.isOpenForFulfillment
import dropnext.dss.lib.shopify.graphql.fulfillment.matchShipmentToFulfillmentOrders
import dropnext.dss.workflow.minimalOrder
import kotlin.test.Test

class FulfillmentOrderMatcherTest {

  @Test
  fun `matches open fulfillment order line items`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result =
        matchShipmentToFulfillmentOrders(
            order,
            shipment(variantId = 101L, quantity = 1),
        )
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.size == 1)
    assert(ok.groups.values.single().single().quantity == 1)
  }

  @Test
  fun `ignores closed fulfillment orders`() {
    val closed =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.CLOSED,
        lineItems = foLineItems(variantId = 101L, remaining = 2),
      )
    val order = orderWithFulfillmentOrders(closed)
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.NotFound)
  }

  @Test
  fun `returns user error when quantity exceeds remaining`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 1))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 2))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `returns not found for unknown variant`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 999L, quantity = 1))
    assert(result is ShipmentMatchResult.NotFound)
  }

  @Test
  fun `treats IN_PROGRESS fulfillment orders as open`() {
    val fo =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.IN_PROGRESS,
        lineItems = foLineItems(variantId = 101L, remaining = 2),
      )
    val order = orderWithFulfillmentOrders(fo)
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
  }

  @Test
  fun `groups line items across multiple fulfillment orders`() {
    val fo1 =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(variantId = 101L, remaining = 5),
      )
    val fo2 =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(variantId = 202L, remaining = 5),
      )
    val order = orderWithFulfillmentOrders(fo1, fo2)
    val shipment =
      Shipment(
        trackingNumber = "TRK",
        carrier = "UPS",
        trackingUrl = null,
        lineItems = listOf(
          ShipmentLineItem(productVariantId = 101L, quantity = 2),
          ShipmentLineItem(productVariantId = 202L, quantity = 1),
        ),
      )
    val result = matchShipmentToFulfillmentOrders(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 2)
    assert(result.groups.entries.single { it.key.id.endsWith("301") }.value.single().quantity == 2)
    assert(result.groups.entries.single { it.key.id.endsWith("302") }.value.single().quantity == 1)
  }

  @Test
  fun `zero quantity (bypassed validation) returns user error`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 0))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `not-found error includes variant id and tracking number`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 999L, quantity = 1))
    val nf = result as ShipmentMatchResult.NotFound
    assert("999" in nf.detail)
    assert("1Z999" in nf.detail)
  }

  @Test
  fun `isOpenForFulfillment is true for OPEN`() {
    assert(FulfillmentOrderStatus.OPEN.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is true for IN_PROGRESS`() {
    assert(FulfillmentOrderStatus.IN_PROGRESS.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for CLOSED`() {
    assert(!FulfillmentOrderStatus.CLOSED.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for CANCELLED`() {
    assert(!FulfillmentOrderStatus.CANCELLED.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for INCOMPLETE`() {
    assert(!FulfillmentOrderStatus.INCOMPLETE.isOpenForFulfillment())
  }

  private fun shipment(variantId: Long, quantity: Int): Shipment =
    Shipment(
      trackingNumber = "1Z999",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(productVariantId = variantId, quantity = quantity)),
    )

  private fun openFo(variantId: Long, remaining: Int): FulfillmentOrder {
    val variant =
      ProductVariant(
        id = "gid://shopify/ProductVariant/$variantId",
        legacyResourceId = variantId.toString(),
      )
    return FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(variant, remaining),
    )
  }

  private fun foLineItems(variantId: Long, remaining: Int): FulfillmentOrderLineItemConnection =
    foLineItems(
      ProductVariant(
        id = "gid://shopify/ProductVariant/$variantId",
        legacyResourceId = variantId.toString(),
      ),
      remaining,
    )

  private fun foLineItems(variant: ProductVariant, remaining: Int): FulfillmentOrderLineItemConnection =
    FulfillmentOrderLineItemConnection(
      edges =
        listOf(
          FulfillmentOrderLineItemEdge(
            node =
              FulfillmentOrderLineItem(
                id = "gid://shopify/FulfillmentOrderLineItem/401",
                remainingQuantity = remaining,
                totalQuantity = remaining,
                variant = variant,
              ),
          ),
        ),
    )

  private fun orderWithFulfillmentOrders(vararg fos: FulfillmentOrder): Order =
    minimalOrder().copy(
      fulfillmentOrders =
        FulfillmentOrderConnection(
          edges = fos.map { FulfillmentOrderEdge(node = it) },
        ),
    )
}
