package dropnext.dss.workflow

import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.dss.lib.json.MonolithJson
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
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
  fun `round-trips CreateShopifyOrderRequest through MonolithJson`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder())
    val json = MonolithJson.encodeToString(CreateShopifyOrderRequest.serializer(), req)
    val decoded = MonolithJson.decodeFromString(CreateShopifyOrderRequest.serializer(), json)
    assert(decoded.shopifySubdomain == "dropnext-staging")
    assert(decoded.shopifyOrderId == 1001L)
    assert(decoded.lineItems.size == 1)
    assert(decoded.lineItems.single().productVariantId == 101L)
    assert(decoded.lineItems.single().fulfillmentOrderId == 301L)
    assert(decoded.fulfillmentStatus == null)
  }

  @Test
  fun `omits line items without resolvable fulfillment_order_id`() {
    val order = minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList()))
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    assert(req.lineItems.isEmpty())
  }
}
