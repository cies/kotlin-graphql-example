package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.fulfillmentcancelmutation.Fulfillment as CancelledFulfillment
import dropnext.graphql.generated.fulfillmentcancelmutation.FulfillmentCancelPayload
import dropnext.graphql.generated.fulfillmentcancelmutation.UserError as CancelUserError
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError as CreateUserError
import dropnext.graphql.generated.getorderfordss.Fulfillment
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
class SyncShopifyShipmentsToFulfillmentsTest {

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
  fun `syncShipments returns NotFound when order is missing`() = runBlocking {
    fake.stubGetOrderForDss(order = null)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
    assert("order 1001 not found" in (result as FulfillmentResult.Err.NotFound).detail)
  }

  @Test
  fun `syncShipments succeeds when there are no existing fulfillments to cancel`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateOk(fulfillmentId = 5000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(5000L))
    assert(fake.calls.map { it.operationName } == listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"))
    assert(fake.calls.first().authorization == "tok")
  }

  @Test
  fun `syncShipments cancels existing fulfillments and then reloads order before creating`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCancelOk(fulfillmentId = 8000L)
    fake.stubFulfillmentCreateOk(fulfillmentId = 9000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Ok<*>)
    assert(
      fake.calls.map { it.operationName } ==
        listOf("GetOrderForDss", "FulfillmentCancelMutation", "GetOrderForDss", "FulfillmentCreateWithLineItems"),
    )
  }

  @Test
  fun `syncShipments ignores already-canceled cancel errors as no-ops`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCancelUserError("Fulfillment is already canceled.")
    fake.stubFulfillmentCreateOk(fulfillmentId = 9001L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(9001L))
  }

  @Test
  fun `syncShipments returns UserError on non-already cancel error`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCancelUserError("fulfillment locked")
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.UserError)
    assert("fulfillment locked" in (result as FulfillmentResult.Err.UserError).messages.single())
  }

  @Test
  fun `syncShipments propagates create UserError`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateUserError("tracking number invalid")
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.UserError)
  }

  @Test
  fun `syncShipments treats Graphql errors on GetOrder as NotFound`() = runBlocking {
    fake.stubRaw(
      "GetOrderForDss",
      """{"data":{"order":null},"errors":[{"message":"throttled"}]}""",
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `syncShipments returns GraphqlError when create-mutation response carries errors`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubRaw(
      "FulfillmentCreateWithLineItems",
      """{"data":{"fulfillmentCreate":{"fulfillment":null,"userErrors":[]}},"errors":[{"message":"throttled"}]}""",
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.GraphqlError)
    assert("throttled" in (result as FulfillmentResult.Err.GraphqlError).raw)
  }

  @Test
  fun `syncShipments returns NotFound when initial loadOrder fails entirely (server down)`() = runBlocking {
    // Bind and immediately close to acquire a port nothing is listening on — connection refused.
    val ghost = FakeShopifyGraphqlServer()
    val deadPort = ghost.start()
    ghost.stop()
    val deadUrl = URI("http://localhost:$deadPort/admin/api/2026-04/graphql.json").toURL()
    val deadShopify = HttpShopifyGraphqlService(
      ShopDomain.parse("acme.myshopify.com")!!,
      GraphQLKtorClient(deadUrl, httpClient),
      "tok",
    )

    val result = syncShopifyShipmentsToFulfillments(deadShopify, syncRequest())
    assert(result is FulfillmentResult.Err.NotFound)
  }

  @Test
  fun `syncShipments returns Network error when a mutation call fails`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    // Malformed JSON forces the graphql client to throw; the workflow maps that to Network.
    fake.stubRaw("FulfillmentCancelMutation", "{not-valid-json")
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.Network)
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

  private fun orderWithFulfillment(id: Long) = minimalOrder().copy(
    fulfillments = listOf(
      Fulfillment(
        id = "gid://shopify/Fulfillment/$id",
        legacyResourceId = id.toString(),
        trackingInfo = emptyList(),
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

  private fun FakeShopifyGraphqlServer.stubFulfillmentCreateOk(fulfillmentId: Long) = stubData(
    "FulfillmentCreateWithLineItems",
    FulfillmentCreateWithLineItems.Result(
      fulfillmentCreate = FulfillmentCreatePayload(
        fulfillment = CreatedFulfillment(
          id = "gid://shopify/Fulfillment/$fulfillmentId",
          legacyResourceId = fulfillmentId.toString(),
        ),
        userErrors = emptyList(),
      ),
    ),
    FulfillmentCreateWithLineItems.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentCreateUserError(message: String) = stubData(
    "FulfillmentCreateWithLineItems",
    FulfillmentCreateWithLineItems.Result(
      fulfillmentCreate = FulfillmentCreatePayload(
        fulfillment = null,
        userErrors = listOf(CreateUserError(field = listOf("tracking"), message = message)),
      ),
    ),
    FulfillmentCreateWithLineItems.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentCancelOk(fulfillmentId: Long) = stubData(
    "FulfillmentCancelMutation",
    FulfillmentCancelMutation.Result(
      fulfillmentCancel = FulfillmentCancelPayload(
        fulfillment = CancelledFulfillment(
          id = "gid://shopify/Fulfillment/$fulfillmentId",
          status = FulfillmentStatus.CANCELLED,
        ),
        userErrors = emptyList(),
      ),
    ),
    FulfillmentCancelMutation.Result.serializer(),
  )

  private fun FakeShopifyGraphqlServer.stubFulfillmentCancelUserError(message: String) = stubData(
    "FulfillmentCancelMutation",
    FulfillmentCancelMutation.Result(
      fulfillmentCancel = FulfillmentCancelPayload(
        fulfillment = null,
        userErrors = listOf(CancelUserError(field = listOf("id"), message = message)),
      ),
    ),
    FulfillmentCancelMutation.Result.serializer(),
  )

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
}
