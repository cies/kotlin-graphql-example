package dropnext.dss.testing.fake

import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.expediagroup.graphql.client.types.GraphQLClientSourceLocation
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookRegistrationReport
import dropnext.graphql.generated.FulfillmentCreateWithTracking
import dropnext.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import dropnext.graphql.generated.GetOrderById
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.fulfillmentcreatewithtracking.FulfillmentCreatePayload as DemoCreatePayload
import dropnext.graphql.generated.fulfillmenttrackinginfoupdatemutation.FulfillmentTrackingInfoUpdatePayload
import dropnext.graphql.generated.getproductbyid.Shop as GetProductByIdShop
import dropnext.graphql.generated.shopidentity.Shop as ShopIdentityShop
import dropnext.graphql.generated.syncproductspage.PageInfo
import dropnext.graphql.generated.syncproductspage.ProductConnection


/**
 * In-memory [ShopifyGraphqlService] for handler/workflow tests. Each method has a settable
 * response stub (with a sensible empty default) and records the arguments it was called with,
 * so tests can both seed behaviour and assert on interactions without spinning up
 * [FakeShopifyGraphqlServer] (HTTP, port allocation, JSON parsing). Reserve the HTTP fake for
 * `HttpShopifyGraphqlServiceTest`, which has to exercise the actual graphql wire format.
 */
class FakeShopifyGraphqlService(
  override val shop: ShopDomain = ShopDomain.parse("acme.myshopify.com")!!,
) : ShopifyGraphqlService {

  // ---------- stubbable responses (sensible empty defaults) ----------

  var shopIdentityResponse: GraphQLClientResponse<ShopIdentity.Result> =
    okResponse(
      ShopIdentity.Result(
        shop = ShopIdentityShop(id = "gid://shopify/Shop/0", myshopifyDomain = shop.host),
      ),
    )

  var syncProductsPageResponse: GraphQLClientResponse<SyncProductsPage.Result> =
    okResponse(
      SyncProductsPage.Result(
        products = ProductConnection(
          pageInfo = PageInfo(hasNextPage = false, endCursor = null),
          edges = emptyList(),
        ),
      ),
    )

  var getProductByIdResponse: GraphQLClientResponse<GetProductById.Result> =
    okResponse(GetProductById.Result(product = null, shop = GetProductByIdShop()))

  var getOrderByIdResponse: GraphQLClientResponse<GetOrderById.Result> =
    okResponse(GetOrderById.Result(order = null))

  var loadOrderForDssResponse: GraphQLClientResponse<GetOrderForDss.Result> =
    okResponse(GetOrderForDss.Result(order = null))

  var demoCreateFulfillmentWithTrackingResponse: GraphQLClientResponse<FulfillmentCreateWithTracking.Result> =
    okResponse(
      FulfillmentCreateWithTracking.Result(
        fulfillmentCreate = DemoCreatePayload(fulfillment = null, userErrors = emptyList()),
      ),
    )

  var demoUpdateFulfillmentTrackingResponse: GraphQLClientResponse<FulfillmentTrackingInfoUpdateMutation.Result> =
    okResponse(
      FulfillmentTrackingInfoUpdateMutation.Result(
        fulfillmentTrackingInfoUpdate = FulfillmentTrackingInfoUpdatePayload(
          fulfillment = null,
          userErrors = emptyList(),
        ),
      ),
    )

  var syncShipmentsWithFulfillmentsResult: FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> =
    FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = emptyList()))

  var createTrackingEventResult: FulfillmentResult<TrackingUpdateResponse> =
    FulfillmentResult.Ok(TrackingUpdateResponse(fulfillmentEventId = 0L))

  var registerStandardWebhooksResult: WebhookRegistrationReport =
    WebhookRegistrationReport(
      activeSubscriptions = emptyList(),
      addedSubscriptions = emptyList(),
      failedTopics = emptyList(),
    )

  // ---------- recorded calls ----------

  val shopIdentityCallCount: Int get() = _shopIdentityCallCount
  private var _shopIdentityCallCount = 0

  data class SyncProductsPageArgs(val first: Int, val after: String?)
  val syncProductsPageCalls = mutableListOf<SyncProductsPageArgs>()

  val getProductByIdCalls = mutableListOf<String>()
  val getOrderByIdCalls = mutableListOf<String>()
  val loadOrderForDssCalls = mutableListOf<String>()
  val syncShipmentsCalls = mutableListOf<SyncShipmentsWithFulfillmentsRequest>()
  val createTrackingEventCalls = mutableListOf<TrackingUpdateRequest>()
  val registerStandardWebhooksCalls = mutableListOf<String>()

  data class DemoCreateFulfillmentArgs(
    val fulfillmentOrderId: String,
    val company: String?,
    val trackingNumber: String?,
    val trackingUrl: String?,
    val notifyCustomer: Boolean,
  )
  val demoCreateFulfillmentCalls = mutableListOf<DemoCreateFulfillmentArgs>()

  data class DemoUpdateTrackingArgs(
    val fulfillmentId: String,
    val company: String?,
    val trackingNumber: String?,
    val trackingUrl: String?,
    val notifyCustomer: Boolean?,
  )
  val demoUpdateTrackingCalls = mutableListOf<DemoUpdateTrackingArgs>()

  // ---------- interface impls ----------

  override suspend fun shopIdentity(): GraphQLClientResponse<ShopIdentity.Result> {
    _shopIdentityCallCount++
    return shopIdentityResponse
  }

  override suspend fun syncProductsPage(
    first: Int,
    after: String?,
  ): GraphQLClientResponse<SyncProductsPage.Result> {
    syncProductsPageCalls.add(SyncProductsPageArgs(first, after))
    return syncProductsPageResponse
  }

  override suspend fun getProductById(productGid: String): GraphQLClientResponse<GetProductById.Result> {
    getProductByIdCalls.add(productGid)
    return getProductByIdResponse
  }

  override suspend fun getOrderById(orderGid: String): GraphQLClientResponse<GetOrderById.Result> {
    getOrderByIdCalls.add(orderGid)
    return getOrderByIdResponse
  }

  override suspend fun loadOrderForDss(orderGid: String): GraphQLClientResponse<GetOrderForDss.Result> {
    loadOrderForDssCalls.add(orderGid)
    return loadOrderForDssResponse
  }

  override suspend fun demoCreateFulfillmentWithTracking(
    fulfillmentOrderId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithTracking.Result> {
    demoCreateFulfillmentCalls.add(
      DemoCreateFulfillmentArgs(fulfillmentOrderId, company, trackingNumber, trackingUrl, notifyCustomer),
    )
    return demoCreateFulfillmentWithTrackingResponse
  }

  override suspend fun demoUpdateFulfillmentTracking(
    fulfillmentId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean?,
  ): GraphQLClientResponse<FulfillmentTrackingInfoUpdateMutation.Result> {
    demoUpdateTrackingCalls.add(
      DemoUpdateTrackingArgs(fulfillmentId, company, trackingNumber, trackingUrl, notifyCustomer),
    )
    return demoUpdateFulfillmentTrackingResponse
  }

  override suspend fun syncShipmentsWithFulfillments(
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
    syncShipmentsCalls.add(payload)
    return syncShipmentsWithFulfillmentsResult
  }

  override suspend fun createTrackingEvent(
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> {
    createTrackingEventCalls.add(payload)
    return createTrackingEventResult
  }

  override suspend fun registerStandardWebhooks(callbackUrl: String): WebhookRegistrationReport {
    registerStandardWebhooksCalls.add(callbackUrl)
    return registerStandardWebhooksResult
  }
}

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
