package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.valueOrNull
import dropnext.dss.testing.fake.FakeShopifyGraphqlService
import dropnext.dss.testing.fake.errorResponse
import dropnext.dss.testing.fake.okResponse
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.fulfillmentcancelmutation.Fulfillment as CancelledFulfillment
import dropnext.graphql.generated.fulfillmentcancelmutation.FulfillmentCancelPayload
import dropnext.graphql.generated.fulfillmentcancelmutation.UserError as CancelUserError
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError as CreateUserError
import kotlin.test.Test
import kotlinx.coroutines.runBlocking


class EffectShopifyMutationsTest {

  @Test
  fun `empty error list on successful cancel then create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.cancelFulfillmentResponse = okResponse(
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = CancelledFulfillment(
            id = "gid://shopify/Fulfillment/8000",
            status = FulfillmentStatus.CANCELLED,
          ),
          userErrors = emptyList(),
        ),
      ),
    )
    fake.createFulfillmentWithLineItemsResponse = createOk(9000L)
    val mutations = listOf(
      ShopifyMutation.FulfillmentCancel("gid://shopify/Fulfillment/8000"),
      createMutation(),
    )
    val result = effectShopifyMutations(fake, mutations)
    assert(result !is Failure)
    assert(result is Success)
    assert(result.valueOrNull() == listOf(9000L))
    assert(fake.cancelFulfillmentCalls == listOf("gid://shopify/Fulfillment/8000"))
    assert(fake.createFulfillmentWithLineItemsCalls.size == 1)
    assert(fake.loadOrderForDssCalls.isEmpty())
  }

  @Test
  fun `already-canceled cancel is not an error`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.cancelFulfillmentResponse = okResponse(
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = null,
          userErrors = listOf(
            CancelUserError(
              field = listOf("id"),
              message = "Fulfillment is already canceled."
            )
          ),
        ),
      ),
    )
    fake.createFulfillmentWithLineItemsResponse = createOk(9001L)
    val result = effectShopifyMutations(
      fake,
      listOf(
        ShopifyMutation.FulfillmentCancel("gid://shopify/Fulfillment/8000"),
        createMutation(),
      ),
    )
    assert(result !is Failure)
    assert(result.valueOrNull() == listOf(9001L))
  }

  @Test
  fun `first create error stops later creates`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentWithLineItemsResponseQueue.add(createOk(5001L))
    fake.createFulfillmentWithLineItemsResponseQueue.add(
      okResponse(
        FulfillmentCreateWithLineItems.Result(
          fulfillmentCreate = FulfillmentCreatePayload(
            fulfillment = null,
            userErrors = listOf(
              CreateUserError(
                field = listOf("tracking"),
                message = "tracking number invalid"
              )
            ),
          ),
        ),
      ),
    )
    val result = effectShopifyMutations(
      fake,
      listOf(
        createMutation(tracking = "TRK-1"),
        createMutation(tracking = "TRK-2"),
      ),
    )
    assert(result is Failure)
    assert((result as Failure).reason is ShopifyError.UserError)
    assert(fake.createFulfillmentWithLineItemsCalls.size == 2)
  }

  @Test
  fun `Graphql error on create is returned and later mutations are not run`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentWithLineItemsResponse = errorResponse(
      "throttled",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(fulfillment = null, userErrors = emptyList()),
      ),
    )
    val result = effectShopifyMutations(
      fake,
      listOf(
        createMutation(tracking = "TRK-1"),
        createMutation(tracking = "TRK-2"),
      ),
    )
    assert(result is Failure)
    assert((result as Failure).reason is ShopifyError.GraphqlError)
    assert(fake.createFulfillmentWithLineItemsCalls.size == 1)
  }

  private fun createOk(fulfillmentId: Long) = okResponse(
    FulfillmentCreateWithLineItems.Result(
      fulfillmentCreate = FulfillmentCreatePayload(
        fulfillment = CreatedFulfillment(
          id = "gid://shopify/Fulfillment/$fulfillmentId",
          legacyResourceId = fulfillmentId.toString(),
        ),
        userErrors = emptyList(),
      ),
    ),
  )

  private fun createMutation(tracking: String = "1Z999") = ShopifyMutation.FulfillmentCreate(
    lineItems = listOf(
      FulfillmentOrderLineItem(
        fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301",
        lineItemId = "gid://shopify/FulfillmentOrderLineItem/401",
        quantity = 1,
      ),
    ),
    trackingNumber = tracking,
    carrier = "UPS",
    trackingUrl = null,
    notifyCustomer = false,
  )
}
