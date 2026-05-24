package dropnext.dss.workflow

import dropnext.dss.lib.monolith.dto.generated.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.dto.generated.OrderLineItem
import dropnext.dss.lib.monolith.dto.generated.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.errorResponse
import dropnext.dss.testing.fake.okResponse
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class MonolithOrderSyncTest {

  @Test
  fun `posts mapped order via FakeMonolithService`() {
    val fake = FakeMonolithService()
    val req = sampleCreateOrderRequest()
    val result = runBlocking {
      postMappedOrderToMonolith(fake, req, "orders/create")
    }
    assert(result is CreateOrderResult.HttpResponseSummary)
    assert(fake.createOrderCallCount == 1)
    assert(fake.lastCreateOrder?.shopifySubdomain == "dropnext-staging")
    assert(fake.lastCreateOrder?.shopifyOrderId == 1001L)
  }

  @Test
  fun `syncShopifyOrderToMonolith loads via Graphql and forwards to monolith`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(
        shopify = shopify,
        monolith = monolith,
        orderGid = "gid://shopify/Order/1001",
        webhookTopic = "orders/create",
      )
    }
    assert(result is CreateOrderResult.HttpResponseSummary)
    assert((result as CreateOrderResult.HttpResponseSummary).status == 200)
    assert(monolith.createOrderCallCount == 1)
    assert(monolith.lastCreateOrder?.shopifyOrderId == 1001L)
    assert(monolith.lastCreateOrder?.shopifySubdomain == "acme")
    assert(shopify.loadOrderForDssCalls.single() == "gid://shopify/Order/1001")
  }

  @Test
  fun `syncShopifyOrderToMonolith returns null when order not found`() {
    // Default loadOrderForDssResponse has order = null → workflow short-circuits and logs.
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService()
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result == null)
    assert(monolith.createOrderCallCount == 0)
  }

  @Test
  fun `syncShopifyOrderToMonolith returns null when Graphql call returns top-level errors`() {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      loadOrderForDssResponse = errorResponse("throttled", data = GetOrderForDss.Result(order = null))
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result == null)
    assert(monolith.createOrderCallCount == 0)
  }

  @Test
  fun `syncShopifyOrderToMonolith returns null when no mapped line items remain`() {
    // Order with no fulfillment orders → mapped lineItems is empty → skip sync.
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService().apply {
      loadOrderForDssResponse = okResponse(
        GetOrderForDss.Result(
          order = minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList())),
        ),
      )
    }
    val result = runBlocking {
      syncShopifyOrderToMonolith(shopify, monolith, "gid://shopify/Order/1001", "orders/create")
    }
    assert(result == null)
    assert(monolith.createOrderCallCount == 0)
  }

  @Test
  fun `surfaces monolith 500 as Error with parsed trace`() {
    val fake = FakeMonolithService().apply {
      createOrderStatus = 500
      createOrderErrorBody =
        """{"error":{"code":"InternalError","message":"fail","trace_id":"fake123"}}"""
    }
    val result = runBlocking {
      postMappedOrderToMonolith(fake, sampleCreateOrderRequest(), "orders/create")
    }
    assert(result is CreateOrderResult.Error)
    val err = result as CreateOrderResult.Error
    assert(err.parsed?.monolithTraceId == "fake123")
  }

  private fun sampleCreateOrderRequest(): CreateShopifyOrderRequest =
    CreateShopifyOrderRequest(
      shopifySubdomain = "dropnext-staging",
      shopifyOrderId = 1001L,
      name = "#1001",
      financialStatus = "paid",
      fulfillmentStatus = null,
      createdAt = "2026-04-25T10:30:00Z",
      shippingAddress = ShippingAddress(
        firstName = null,
        lastName = null,
        address1 = "",
        address2 = null,
        city = "",
        province = null,
        provinceCode = null,
        countryCode = "US",
        zip = null,
        phone = null,
      ),
      lineItems = listOf(
        OrderLineItem(
          shopifyLineItemId = 201L,
          productVariantId = 101L,
          quantity = 1,
          fulfillmentOrderId = 0L,
          snapshotOfVariantTitle = "Item",
          snapshotOfProductTitle = "Product",
          snapshotOfPriceInMinorUnits = 1999L,
        ),
      ),
      totalInMinorUnits = 1999L,
      currency = "USD",
    )
}
