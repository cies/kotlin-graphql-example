package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEvent as CreatedFulfillmentEvent
import dropnext.graphql.generated.fulfillmenteventcreatemutation.FulfillmentEventCreatePayload
import dropnext.graphql.generated.fulfillmenteventcreatemutation.UserError as EventUserError
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.FulfillmentTrackingInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncShopifyTrackingEventTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeAll
  fun startServer() {
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
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, "tok")
  }

  @AfterAll
  fun stopServer() {
    httpClient.close()
    fake.stop()
  }

  @BeforeTest
  fun clearFakeBetweenTests() {
    fake.reset()
  }

  @Test
  fun `createTrackingEvent rejects unsupported status as UserError`() = runBlocking {
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(status = "yeeted"))
    assert(result is FulfillmentResult.Err.UserError)
    assert("unsupported tracking status" in (result as FulfillmentResult.Err.UserError).messages.single())
    assert(fake.calls.isEmpty())
  }

  @Test
  fun `createTrackingEvent returns NotFound when order is missing`() = runBlocking {
    fake.stubGetOrderForDss(order = null)
    val result = syncShopifyTrackingEvent(shopify, trackingRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `createTrackingEvent returns NotFound when no fulfillment has the tracking number`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "OTHER-TRACK"))
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert(result is FulfillmentResult.Err.NotFound)
    assert("no fulfillment with tracking number 1Z999" in (result as FulfillmentResult.Err.NotFound).detail)
  }

  @Test
  fun `createTrackingEvent succeeds when the tracking number matches`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "1Z999"))
    fake.stubFulfillmentEventCreateOk(eventId = 7777L)
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    val response = result.unwrapOk<TrackingUpdateResponse>()
    assert(response.fulfillmentEventId == 7777L)
  }

  @Test
  fun `createTrackingEvent propagates event-creation UserError`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithTracking(trackingNumber = "1Z999"))
    fake.stubFulfillmentEventCreateUserError("happenedAt invalid")
    val result = syncShopifyTrackingEvent(shopify, trackingRequest(trackingNumber = "1Z999"))
    assert(result is FulfillmentResult.Err.UserError)
  }

  // ---------- helpers ----------

  /** Type-safe unwrap of a successful [FulfillmentResult] payload — keeps tests free of double casts. */
  @Suppress("UNCHECKED_CAST")
  private inline fun <reified T> FulfillmentResult<*>.unwrapOk(): T {
    assert(this is FulfillmentResult.Ok<*>)
    val value = (this as FulfillmentResult.Ok<*>).value
    assert(value is T)
    return value as T
  }

  private fun orderWithTracking(trackingNumber: String) = minimalOrder().copy(
    fulfillments = listOf(
      Fulfillment(
        id = "gid://shopify/Fulfillment/5000",
        legacyResourceId = "5000",
        trackingInfo = listOf(FulfillmentTrackingInfo(number = trackingNumber)),
      ),
    ),
  )

  private fun FakeShopifyGraphqlServer.stubGetOrderForDss(
    order: dropnext.graphql.generated.getorderfordss.Order?,
  ) = stubData(
    "GetOrderForDss",
    GetOrderForDss.Result(order = order),
    GetOrderForDss.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentEventCreateOk(eventId: Long) = stubData(
    "FulfillmentEventCreateMutation",
    FulfillmentEventCreateMutation.Result(
      fulfillmentEventCreate = FulfillmentEventCreatePayload(
        fulfillmentEvent = CreatedFulfillmentEvent(
          id = "gid://shopify/FulfillmentEvent/$eventId",
          status = FulfillmentEventStatus.IN_TRANSIT,
          message = null,
        ),
        userErrors = emptyList(),
      ),
    ),
    FulfillmentEventCreateMutation.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentEventCreateUserError(message: String) = stubData(
    "FulfillmentEventCreateMutation",
    FulfillmentEventCreateMutation.Result(
      fulfillmentEventCreate = FulfillmentEventCreatePayload(
        fulfillmentEvent = null,
        userErrors = listOf(EventUserError(field = listOf("fulfillmentEvent"), message = message)),
      ),
    ),
    FulfillmentEventCreateMutation.Result.serializer(),
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
