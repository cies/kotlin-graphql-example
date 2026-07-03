package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
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
    assert(ok.skipped.isEmpty())
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
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  @Test
  fun `returns user error when quantity exceeds remaining`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 1))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 2))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `partial match skips unknown variant`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val shipment =
      Shipment(
        trackingNumber = "1Z999",
        carrier = "UPS",
        trackingUrl = null,
        lineItems = listOf(
          ShipmentLineItem(productVariantId = 101L, quantity = 1),
          ShipmentLineItem(productVariantId = 999L, quantity = 1),
        ),
      )
    val result = matchShipmentToFulfillmentOrders(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 1)
    assert(result.groups.values.single().single().quantity == 1)
    assert(result.skipped.size == 1)
    assert(result.skipped.single().productVariantId == 999L)
    assert(result.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  @Test
  fun `all lines skipped returns empty groups`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 999L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.size == 1)
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
  fun `diagram cross-FO shipment`() {
    val fo1 =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 401L, variantId = 1L, remaining = 5),
      )
    val fo2 =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 402L, variantId = 5L, remaining = 5),
      )
    val order = orderWithFulfillmentOrders(fo1, fo2)
    val shipment =
      Shipment(
        trackingNumber = "TRK-A",
        carrier = "UPS",
        trackingUrl = null,
        lineItems = listOf(
          ShipmentLineItem(productVariantId = 1L, quantity = 1),
          ShipmentLineItem(productVariantId = 5L, quantity = 1),
        ),
      )
    val result = matchShipmentToFulfillmentOrders(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 2)
    assert(result.skipped.isEmpty())
  }

  @Test
  fun `duplicate variant rows aggregated`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 5))
    val shipment =
      Shipment(
        trackingNumber = "1Z999",
        carrier = "UPS",
        trackingUrl = null,
        lineItems = listOf(
          ShipmentLineItem(productVariantId = 101L, quantity = 2),
          ShipmentLineItem(productVariantId = 101L, quantity = 1),
        ),
      )
    val normalized = normalizeShipmentLineItems(shipment)
    assert(normalized.size == 1)
    assert(normalized.single().quantity == 3)

    val result = matchShipmentToFulfillmentOrders(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 1)
    assert(result.groups.values.single().single().quantity == 3)
  }

  @Test
  fun `cross-shipment over-allocation fails dry-run`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val shipments =
      listOf(
        shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
        shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
      )
    val result = dryRunAllShipments(order, shipments)
    assert(result is DryRunResult.UserError)
  }

  @Test
  fun `cross-shipment allocation within limits succeeds dry-run`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 3))
    val shipments =
      listOf(
        shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
        shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
      )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.perShipment.size == 2)
    assert(result.totalSkipped == 0)
  }

  @Test
  fun `null variant legacyResourceId skipped safely`() {
    val badLine =
      FulfillmentOrderLineItem(
        id = "gid://shopify/FulfillmentOrderLineItem/401",
        remainingQuantity = 5,
        totalQuantity = 5,
        variant =
          ProductVariant(
            id = "gid://shopify/ProductVariant/101",
            legacyResourceId = "not-a-number",
          ),
      )
    val goodFo =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems =
          FulfillmentOrderLineItemConnection(
            edges = listOf(FulfillmentOrderLineItemEdge(node = badLine)),
          ),
      )
    val goodFo2 = openFo(variantId = 101L, remaining = 5, foId = 302L, lineItemId = 402L)
    val order = orderWithFulfillmentOrders(goodFo, goodFo2)
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
  }

  @Test
  fun `closed FO with zero remaining skipped`() {
    val closedFo =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.CLOSED,
        lineItems = foLineItems(variantId = 101L, remaining = 0),
      )
    val openFoZero =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 402L, variantId = 101L, remaining = 0),
      )
    val order = orderWithFulfillmentOrders(closedFo, openFoZero)
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.ZERO_REMAINING)
  }

  @Test
  fun `prefers fulfillment order with highest remaining quantity`() {
    val foLow =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 401L, variantId = 101L, remaining = 1),
      )
    val foHigh =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 402L, variantId = 101L, remaining = 5),
      )
    val order = orderWithFulfillmentOrders(foLow, foHigh)
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 2))
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.keys.single().id.endsWith("302"))
  }

  @Test
  fun `zero quantity (bypassed validation) returns user error`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 101L, quantity = 0))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `skipped line includes variant id and tracking number`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchShipmentToFulfillmentOrders(order, shipment(variantId = 999L, quantity = 1))
    val ok = result as ShipmentMatchResult.Ok
    val skipped = ok.skipped.single()
    assert(skipped.productVariantId == 999L)
    assert(skipped.trackingNumber == "1Z999")
  }

  @Test
  fun `ledger initializes remaining quantities from open fulfillment order lines`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 3))
    val ledger = FulfillmentQuantityLedger(order)
    assert(ledger.remaining("gid://shopify/FulfillmentOrderLineItem/401") == 3)
  }

  @Test
  fun `ledger ignores closed fulfillment orders and zero remaining lines`() {
    val closed =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.CLOSED,
        lineItems = foLineItems(variantId = 101L, remaining = 5),
      )
    val openZero =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/302",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = foLineItems(lineItemId = 402L, variantId = 202L, remaining = 0),
      )
    val order = orderWithFulfillmentOrders(closed, openZero)
    val ledger = FulfillmentQuantityLedger(order)
    assert(ledger.remaining("gid://shopify/FulfillmentOrderLineItem/401") == 0)
    assert(ledger.remaining("gid://shopify/FulfillmentOrderLineItem/402") == 0)
  }

  @Test
  fun `ledger tryConsume decrements remaining quantity`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 3))
    val ledger = FulfillmentQuantityLedger(order)
    val gid = "gid://shopify/FulfillmentOrderLineItem/401"
    assert(ledger.tryConsume(gid, 2))
    assert(ledger.remaining(gid) == 1)
    assert(ledger.tryConsume(gid, 1))
    assert(ledger.remaining(gid) == 0)
  }

  @Test
  fun `ledger tryConsume returns false when quantity exceeds remaining`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val ledger = FulfillmentQuantityLedger(order)
    val gid = "gid://shopify/FulfillmentOrderLineItem/401"
    assert(!ledger.tryConsume(gid, 3))
    assert(ledger.remaining(gid) == 2)
  }

  @Test
  fun `ledger tryConsume returns false for unknown line item gid`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val ledger = FulfillmentQuantityLedger(order)
    assert(!ledger.tryConsume("gid://shopify/FulfillmentOrderLineItem/999", 1))
  }

  @Test
  fun `dry-run reports total skipped lines across shipments`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val shipments =
      listOf(
        Shipment(
          trackingNumber = "TRK-1",
          carrier = "UPS",
          trackingUrl = null,
          lineItems = listOf(
            ShipmentLineItem(productVariantId = 101L, quantity = 1),
            ShipmentLineItem(productVariantId = 999L, quantity = 1),
          ),
        ),
        shipment(tracking = "TRK-2", variantId = 888L, quantity = 1),
      )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.totalSkipped == 2)
    assert(result.perShipment.size == 2)
    assert(result.perShipment.first().skipped.size == 1)
    assert(result.perShipment.last().skipped.size == 1)
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

  private fun shipment(variantId: Long, quantity: Int, tracking: String = "1Z999"): Shipment =
    Shipment(
      trackingNumber = tracking,
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(productVariantId = variantId, quantity = quantity)),
    )

  private fun openFo(
    variantId: Long,
    remaining: Int,
    foId: Long = 301L,
    lineItemId: Long = 401L,
  ): FulfillmentOrder {
    val variant =
      ProductVariant(
        id = "gid://shopify/ProductVariant/$variantId",
        legacyResourceId = variantId.toString(),
      )
    return FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/$foId",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(lineItemId, variant, remaining),
    )
  }

  private fun foLineItems(variantId: Long, remaining: Int): FulfillmentOrderLineItemConnection =
    foLineItems(401L, variantId, remaining)

  private fun foLineItems(
    lineItemId: Long,
    variantId: Long,
    remaining: Int,
  ): FulfillmentOrderLineItemConnection =
    foLineItems(
      lineItemId,
      ProductVariant(
        id = "gid://shopify/ProductVariant/$variantId",
        legacyResourceId = variantId.toString(),
      ),
      remaining,
    )

  private fun foLineItems(
    lineItemId: Long,
    variant: ProductVariant,
    remaining: Int,
  ): FulfillmentOrderLineItemConnection =
    FulfillmentOrderLineItemConnection(
      edges =
        listOf(
          FulfillmentOrderLineItemEdge(
            node =
              FulfillmentOrderLineItem(
                id = "gid://shopify/FulfillmentOrderLineItem/$lineItemId",
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
