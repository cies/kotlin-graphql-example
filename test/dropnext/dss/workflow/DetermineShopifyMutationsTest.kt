package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.graphql.generated.getorderfordss.Fulfillment
import kotlin.test.Test
import kotlinx.coroutines.runBlocking


class DetermineShopifyMutationsTest {

  @Test
  fun `loads the order once and does not cancel or create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(minimalOrder())
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Success)
    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `missing order is NotFound`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.NotFound("order 1001 not found"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.NotFound)
    assert("order 1001 not found" in error.message)
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `Graphql errors with no order are GraphqlError`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.GraphqlError("throttled"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.GraphqlError)
    assert("throttled" in error.message)
  }

  @Test
  fun `load exception is Network`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.Network("connection refused"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.Network)
    assert("connection refused" in error.message)
  }

  @Test
  fun `existing fulfillment is not planned as a cancel`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(
      minimalOrder().copy(
        fulfillments = listOf(
          Fulfillment(
            id = "gid://shopify/Fulfillment/8000",
            legacyResourceId = "8000",
            trackingInfo = emptyList(),
          ),
        ),
      ),
    )
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    val mutations = (result as Success).value
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    assert(mutations.single() is ShopifyMutation.FulfillmentCreate)
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  private fun shipment(): Shipment =
    Shipment(
      trackingNumber = "1Z999",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
    )
}
