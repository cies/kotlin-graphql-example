package dropnext.dss.lib.shopify

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.ShipmentLineItem
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.dss.workflow.minimalOrder
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.fulfillmentcancelmutation.FulfillmentCancelPayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEventCreatePayload
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.FulfillmentTrackingInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class ShopifyGraphqlServiceTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeTest
  fun setUp() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = HttpClient(OkHttp) {
      engine {
        config {
          connectTimeout(2, TimeUnit.SECONDS)
          readTimeout(5, TimeUnit.SECONDS)
        }
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
    }
    val url = URI("http://localhost:$port/admin/api/2026-04/graphql.json").toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = ShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, "tok")
  }

  @AfterTest
  fun tearDown() {
    httpClient.close()
    fake.stop()
  }

  // ---------- syncShipmentsWithFulfillments ----------

  @Test
  fun `syncShipments returns NotFound when order is missing`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = null),
      GetOrderForDss.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
    assert("order 1001 not found" in (result as FulfillmentResult.Err.NotFound).detail)
  }

  @Test
  fun `syncShipments succeeds when there are no existing fulfillments to cancel`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder().copy(fulfillments = emptyList())),
      GetOrderForDss.Result.serializer(),
    )
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment(
            id = "gid://shopify/Fulfillment/5000",
            legacyResourceId = "5000",
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Ok<*>)
    assert((result as FulfillmentResult.Ok<*>).value.let { (it as dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse).newFulfillmentIds } == listOf(5000L))
    val ops = fake.calls.map { it.operationName }
    assert(ops == listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"))
    assert(fake.calls.first().authorization == "tok")
  }

  @Test
  fun `syncShipments cancels existing fulfillments and then reloads order before creating`() = runBlocking {
    val orderWithExisting = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/8000",
          legacyResourceId = "8000",
          trackingInfo = emptyList(),
        ),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithExisting), GetOrderForDss.Result.serializer())
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = dropnext.graphql.generated.fulfillmentcancelmutation.Fulfillment(
            id = "gid://shopify/Fulfillment/8000",
            status = FulfillmentStatus.CANCELLED,
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment(
            id = "gid://shopify/Fulfillment/9000",
            legacyResourceId = "9000",
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Ok<*>)
    val ops = fake.calls.map { it.operationName }
    assert(ops == listOf("GetOrderForDss", "FulfillmentCancelMutation", "GetOrderForDss", "FulfillmentCreateWithLineItems"))
  }

  @Test
  fun `syncShipments ignores already-canceled cancel errors as no-ops`() = runBlocking {
    val orderWithExisting = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(id = "gid://shopify/Fulfillment/8000", legacyResourceId = "8000", trackingInfo = emptyList()),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithExisting), GetOrderForDss.Result.serializer())
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = null,
          userErrors = listOf(
            dropnext.graphql.generated.fulfillmentcancelmutation.UserError(
              field = listOf("id"),
              message = "Fulfillment is already canceled.",
            ),
          ),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment(
            id = "gid://shopify/Fulfillment/9001",
            legacyResourceId = "9001",
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Ok<*>)
    val value = (result as FulfillmentResult.Ok<*>).value as dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
    assert(value.newFulfillmentIds == listOf(9001L))
  }

  @Test
  fun `syncShipments returns UserError on non-already cancel error`() = runBlocking {
    val orderWithExisting = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(id = "gid://shopify/Fulfillment/8000", legacyResourceId = "8000", trackingInfo = emptyList()),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithExisting), GetOrderForDss.Result.serializer())
    fake.stubData(
      "FulfillmentCancelMutation",
      FulfillmentCancelMutation.Result(
        fulfillmentCancel = FulfillmentCancelPayload(
          fulfillment = null,
          userErrors = listOf(
            dropnext.graphql.generated.fulfillmentcancelmutation.UserError(
              field = listOf("id"),
              message = "fulfillment locked",
            ),
          ),
        ),
      ),
      FulfillmentCancelMutation.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.UserError)
    assert("fulfillment locked" in (result as FulfillmentResult.Err.UserError).messages.single())
  }

  @Test
  fun `syncShipments propagates create UserError`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder().copy(fulfillments = emptyList())),
      GetOrderForDss.Result.serializer(),
    )
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = null,
          userErrors = listOf(
            dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError(
              field = listOf("tracking"),
              message = "tracking number invalid",
            ),
          ),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.UserError)
  }

  // ---------- createTrackingEvent ----------

  @Test
  fun `createTrackingEvent rejects unsupported status as UserError`() = runBlocking {
    val result = shopify.createTrackingEvent(trackingRequest(status = "yeeted"))
    assert(result is FulfillmentResult.Err.UserError)
    assert("unsupported tracking status" in (result as FulfillmentResult.Err.UserError).messages.single())
    assert(fake.calls.isEmpty())
  }

  @Test
  fun `createTrackingEvent returns NotFound when order is missing`() = runBlocking {
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
    val result = shopify.createTrackingEvent(trackingRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `createTrackingEvent returns NotFound when no fulfillment has the tracking number`() = runBlocking {
    val orderWithDifferentTracking = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/5000",
          legacyResourceId = "5000",
          trackingInfo = listOf(FulfillmentTrackingInfo(number = "OTHER-TRACK")),
        ),
      ),
    )
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = orderWithDifferentTracking),
      GetOrderForDss.Result.serializer(),
    )
    val result = shopify.createTrackingEvent(trackingRequest(trackingNumber = "1Z999"))
    assert(result is FulfillmentResult.Err.NotFound)
    assert("no fulfillment with tracking number 1Z999" in (result as FulfillmentResult.Err.NotFound).detail)
  }

  @Test
  fun `createTrackingEvent succeeds when the tracking number matches`() = runBlocking {
    val orderWithTracking = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/5000",
          legacyResourceId = "5000",
          trackingInfo = listOf(FulfillmentTrackingInfo(number = "1Z999")),
        ),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithTracking), GetOrderForDss.Result.serializer())
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(
          fulfillmentEvent = dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEvent(
            id = "gid://shopify/FulfillmentEvent/7777",
            status = FulfillmentEventStatus.IN_TRANSIT,
            message = null,
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createTrackingEvent(trackingRequest(trackingNumber = "1Z999"))
    assert(result is FulfillmentResult.Ok<*>)
    val value = (result as FulfillmentResult.Ok<*>).value as dropnext.dss.lib.dto.TrackingUpdateResponse
    assert(value.fulfillmentEventId == 7777L)
  }

  @Test
  fun `createTrackingEvent propagates event-creation UserError`() = runBlocking {
    val orderWithTracking = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/5000",
          legacyResourceId = "5000",
          trackingInfo = listOf(FulfillmentTrackingInfo(number = "1Z999")),
        ),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithTracking), GetOrderForDss.Result.serializer())
    fake.stubData(
      "FulfillmentEventCreateMutation",
      FulfillmentEventCreateMutation.Result(
        fulfillmentEventCreate = FulfillmentEventCreatePayload(
          fulfillmentEvent = null,
          userErrors = listOf(
            dropnext.graphql.generated.fulfillmenteventcreatemutation.UserError(
              field = listOf("fulfillmentEvent"),
              message = "happenedAt invalid",
            ),
          ),
        ),
      ),
      FulfillmentEventCreateMutation.Result.serializer(),
    )
    val result = shopify.createTrackingEvent(trackingRequest(trackingNumber = "1Z999"))
    assert(result is FulfillmentResult.Err.UserError)
  }

  @Test
  fun `syncShipments treats Graphql errors on GetOrder as NotFound`() = runBlocking {
    fake.stubRaw(
      "GetOrderForDss",
      """{"data":{"order":null},"errors":[{"message":"throttled"}]}""",
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `syncShipments returns GraphqlError when create-mutation response carries errors`() = runBlocking {
    fake.stubData(
      "GetOrderForDss",
      GetOrderForDss.Result(order = minimalOrder().copy(fulfillments = emptyList())),
      GetOrderForDss.Result.serializer(),
    )
    fake.stubRaw(
      "FulfillmentCreateWithLineItems",
      """{"data":{"fulfillmentCreate":{"fulfillment":null,"userErrors":[]}},"errors":[{"message":"throttled"}]}""",
    )
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.GraphqlError)
    assert("throttled" in (result as FulfillmentResult.Err.GraphqlError).raw)
  }

  @Test
  fun `syncShipments returns NotFound when initial loadOrder fails entirely (server down)`() = runBlocking {
    fake.stop()
    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `syncShipments returns Network error when a mutation call fails`() = runBlocking {
    val orderWithExisting = minimalOrder().copy(
      fulfillments = listOf(
        Fulfillment(id = "gid://shopify/Fulfillment/8000", legacyResourceId = "8000", trackingInfo = emptyList()),
      ),
    )
    fake.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithExisting), GetOrderForDss.Result.serializer())
    fake.stubRaw("FulfillmentCancelMutation", "{not-valid-json")

    val result = shopify.syncShipmentsWithFulfillments(syncRequest())
    assert(result is FulfillmentResult.Err.Network)
  }

  // ---------- helpers ----------

  private fun syncRequest(): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = listOf(
        Shipment(
          trackingNumber = "1Z999",
          carrier = "UPS",
          trackingUrl = null,
          lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
        ),
      ),
    )

  private fun trackingRequest(
    trackingNumber: String = "1Z999",
    status: String = "in_transit",
  ): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      trackingNumber = trackingNumber,
      status = status,
      happenedAt = "2026-04-02T08:30:00Z",
      message = null,
    )
}
