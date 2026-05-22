package dropnext.dss.workflow

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.OrderLineItem
import dropnext.dss.lib.dss.dto.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.testing.fake.FakeMonolithService
import kotlin.test.Test

class WebhookMonolithSyncTest {

  @Test
  fun `posts mapped order via FakeMonolithService`() {
    val fake = FakeMonolithService()
    val req = sampleCreateOrderRequest()
    val result = kotlinx.coroutines.runBlocking {
      postMappedOrderToMonolith(fake, req, "orders/create")
    }
    assert(result is CreateOrderResult.HttpResponseSummary)
    assert(fake.createOrderCallCount == 1)
    assert(fake.lastCreateOrder?.shopifySubdomain == "dropnext-staging")
    assert(fake.lastCreateOrder?.shopifyOrderId == 1001L)
  }

  @Test
  fun `surfaces monolith 500 as Error with parsed trace`() {
    val fake =
      FakeMonolithService().apply {
        createOrderStatus = 500
        createOrderErrorBody =
          """{"error":{"code":"InternalError","message":"fail","trace_id":"fake123"}}"""
      }
    val result =
      kotlinx.coroutines.runBlocking {
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
      shippingAddress =
        ShippingAddress(
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
      lineItems =
        listOf(
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
