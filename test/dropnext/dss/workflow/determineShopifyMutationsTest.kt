package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.errorResponse
import dropnext.dss.testing.fake.okResponse
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.getorderfordss.Fulfillment
import java.io.IOException
import kotlin.test.Test
import kotlinx.coroutines.runBlocking


class DetermineShopifyMutationsTest {

  @Test
  fun `loads the order once and does not cancel or create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = minimalOrder()))
    val result = determineShopifyMutations(fake, shopifyOrderId = 1001L, shipments = listOf(shipment()))
    assert(result is Success)
    assert(fake.loadOrderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentWithLineItemsCalls.isEmpty())
  }

  @Test
  fun `missing order is NotFound`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.loadOrderForDssResponse = okResponse(GetOrderForDss.Result(order = null))
    val result = determineShopifyMutations(fake, shopifyOrderId = 1001L, shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is DetermineShopifyMutationsError.NotFound)
    assert("order 1001 not found" in (error as DetermineShopifyMutationsError.NotFound).detail)
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `Graphql errors with no order are GraphqlError`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.loadOrderForDssResponse = errorResponse("throttled", GetOrderForDss.Result(order = null))
    val result = determineShopifyMutations(fake, shopifyOrderId = 1001L, shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is DetermineShopifyMutationsError.GraphqlError)
    assert("throttled" in (error as DetermineShopifyMutationsError.GraphqlError).raw)
  }

  @Test
  fun `load exception is Network`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.loadOrderForDssException = IOException("connection refused")
    val result = determineShopifyMutations(fake, shopifyOrderId = 1001L, shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is DetermineShopifyMutationsError.Network)
    assert("connection refused" in (error as DetermineShopifyMutationsError.Network).message)
  }

  @Test
  fun `existing fulfillment is included as a cancel in the planned list`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.loadOrderForDssResponse = okResponse(
      GetOrderForDss.Result(
        order = minimalOrder().copy(
          fulfillments = listOf(
            Fulfillment(
              id = "gid://shopify/Fulfillment/8000",
              legacyResourceId = "8000",
              trackingInfo = emptyList(),
            ),
          ),
        ),
      ),
    )
    val result = determineShopifyMutations(fake, shopifyOrderId = 1001L, shipments = listOf(shipment()))
    val mutations = (result as Success).value
    assert(mutations.first() is ShopifyMutation.FulfillmentCancel)
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentWithLineItemsCalls.isEmpty())
  }

  private fun shipment(): Shipment =
    Shipment(
      trackingNumber = "1Z999",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
    )
}
