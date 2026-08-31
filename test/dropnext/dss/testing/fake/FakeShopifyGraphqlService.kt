package dropnext.dss.testing.fake

import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.expediagroup.graphql.client.types.GraphQLClientSourceLocation
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.fulfillmentcancelmutation.Fulfillment as CancelledFulfillment
import dropnext.graphql.generated.fulfillmentcancelmutation.FulfillmentCancelPayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError as CreateUserError
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEventCreatePayload
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import dropnext.graphql.generated.getproductbyid.Shop as GetProductByIdShop
import dropnext.graphql.generated.shopidentity.Shop as ShopIdentityShop
import dropnext.graphql.generated.syncproductspage.PageInfo
import dropnext.graphql.generated.syncproductspage.ProductConnection


/**
 * In-memory [ShopifyGraphqlService] for handler/workflow tests. Each method exposed via this fake
 * has either a stubbable response field or a recording list — but only for the ones tests actually
 * read today. Adding more (e.g. `getProductByIdCalls`) is intentionally a 3-line change at the
 * point a test starts to need it; the fake should not carry speculative surface area.
 *
 * Reserve [FakeShopifyGraphqlServer] (HTTP fake) for `HttpShopifyGraphqlServiceTest`, which has to
 * exercise the actual graphql wire format.
 */
class FakeShopifyGraphqlService(
  override val shop: ShopDomain = ShopDomain.parse("acme.myshopify.com")!!,
) : ShopifyGraphqlService {

  // ---------- stubbable responses + recordings (only what tests use today) ----------

  var shopIdentityResponse: GraphQLClientResponse<ShopIdentity.Result> =
    okResponse(
      ShopIdentity.Result(
        shop = ShopIdentityShop(id = "gid://shopify/Shop/0", myshopifyDomain = shop.normalizedShopifyHost),
      ),
    )
  var shopIdentityCallCount: Int = 0
    private set

  var loadOrderForDssResponse: GraphQLClientResponse<GetOrderForDss.Result> =
    okResponse(GetOrderForDss.Result(order = null))
  val loadOrderForDssResponseQueue: MutableList<GraphQLClientResponse<GetOrderForDss.Result>> = mutableListOf()
  val loadOrderForDssCalls: MutableList<String> = mutableListOf()
  var loadOrderForDssException: Throwable? = null

  var cancelFulfillmentResponse: GraphQLClientResponse<FulfillmentCancelMutation.Result> =
    okResponse(
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(fulfillment = null, userErrors = emptyList()),
      ),
    )
  val cancelFulfillmentCalls: MutableList<String> = mutableListOf()

  var createFulfillmentWithLineItemsResponse: GraphQLClientResponse<FulfillmentCreateWithLineItems.Result> =
    okResponse(
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(fulfillment = null, userErrors = emptyList()),
      ),
    )
  val createFulfillmentWithLineItemsResponseQueue:
    MutableList<GraphQLClientResponse<FulfillmentCreateWithLineItems.Result>> = mutableListOf()
  val createFulfillmentWithLineItemsCalls: MutableList<RecordedCreateFulfillmentCall> = mutableListOf()

  var getProductByIdResponse: GraphQLClientResponse<GetProductById.Result> =
    okResponse(GetProductById.Result(product = null, shop = GetProductByIdShop()))
  val getProductByIdCalls: MutableList<String> = mutableListOf()

  /** Each entry is the `(topic, callbackUrl, includeFields)` that was sent to `registerWebhook`. */
  val registerWebhookCalls: MutableList<Triple<WebhookSubscriptionTopic, String, List<String>?>> = mutableListOf()

  // ---------- interface impls ----------

  override suspend fun shopIdentity(): GraphQLClientResponse<ShopIdentity.Result> {
    shopIdentityCallCount++
    return shopIdentityResponse
  }

  override suspend fun syncProductsPage(
    first: Int,
    after: String?,
  ): GraphQLClientResponse<SyncProductsPage.Result> =
    okResponse(
      SyncProductsPage.Result(
        products = ProductConnection(
          pageInfo = PageInfo(hasNextPage = false, endCursor = null),
          edges = emptyList(),
        ),
      ),
    )

  override suspend fun getProductById(productGid: String): GraphQLClientResponse<GetProductById.Result> {
    getProductByIdCalls.add(productGid)
    return getProductByIdResponse
  }

  override suspend fun loadOrderForDss(orderGid: String): GraphQLClientResponse<GetOrderForDss.Result> {
    loadOrderForDssCalls.add(orderGid)
    loadOrderForDssException?.let { throw it }
    if (loadOrderForDssResponseQueue.isNotEmpty()) {
      return loadOrderForDssResponseQueue.removeAt(0)
    }
    return loadOrderForDssResponse
  }

  override suspend fun cancelFulfillment(
    fulfillmentGid: String,
  ): GraphQLClientResponse<FulfillmentCancelMutation.Result> {
    cancelFulfillmentCalls.add(fulfillmentGid)
    return cancelFulfillmentResponse
  }

  override suspend fun createFulfillmentWithLineItems(
      lineItemsByFulfillmentOrder: List<FulfillmentOrderLineItemsInput>,
      tracking: FulfillmentTrackingInput,
      notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithLineItems.Result> {
    createFulfillmentWithLineItemsCalls.add(
      RecordedCreateFulfillmentCall(
        lineItemsByFulfillmentOrder = lineItemsByFulfillmentOrder,
        tracking = tracking,
      ),
    )
    if (createFulfillmentWithLineItemsResponseQueue.isNotEmpty()) {
      return createFulfillmentWithLineItemsResponseQueue.removeAt(0)
    }
    return createFulfillmentWithLineItemsResponse
  }

  override suspend fun createFulfillmentEvent(
    input: FulfillmentEventInput,
  ): GraphQLClientResponse<FulfillmentEventCreateMutation.Result> =
    okResponse(
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(
          fulfillmentEvent = null,
          userErrors = emptyList(),
        ),
      ),
    )

  override suspend fun getWebhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String,
  ): GraphQLClientResponse<GetWebhookSubscriptions.Result> =
    okResponse(
      GetWebhookSubscriptions.Result(
        webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList()),
      ),
    )

  override suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): GraphQLClientResponse<RegisterWebhook.Result> {
    registerWebhookCalls.add(Triple(topic, callbackUrl, includeFields))
    return okResponse(
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          webhookSubscription = null,
          userErrors = emptyList(),
        ),
      ),
    )
  }
}

data class RecordedCreateFulfillmentCall(
  val lineItemsByFulfillmentOrder: List<FulfillmentOrderLineItemsInput>,
  val tracking: FulfillmentTrackingInput,
)

/** Build a [GraphQLClientResponse] with only `data` populated — the common case for stubs. */
fun <T> okResponse(data: T): GraphQLClientResponse<T> =
  FakeGraphQLResponse(data = data)

/** Build a [GraphQLClientResponse] with a single error message — for testing the error path. */
fun <T> errorResponse(message: String, data: T? = null): GraphQLClientResponse<T> =
  FakeGraphQLResponse(data = data, errors = listOf(FakeGraphQLError(message)))

private data class FakeGraphQLResponse<T>(
  override val data: T? = null,
  override val errors: List<GraphQLClientError>? = null,
  override val extensions: Map<String, Any?>? = null,
) : GraphQLClientResponse<T>

private data class FakeGraphQLError(
  override val message: String,
  override val locations: List<GraphQLClientSourceLocation>? = null,
  override val path: List<Any>? = null,
  override val extensions: Map<String, Any?>? = null,
) : GraphQLClientError
