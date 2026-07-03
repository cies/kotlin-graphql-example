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
import dropnext.dss.lib.shopify.graphql.fulfillment.diagramCrossFoOrder
import dropnext.dss.lib.shopify.graphql.fulfillment.diagramCrossFoShipment
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncShopifyShipmentsToFulfillmentsTest {

  private val orderJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    assert(
      fake.calls.map { it.operationName } ==
        listOf("GetOrderForDss", "FulfillmentCreateWithLineItems", "GetOrderForDss"),
    )
    assert(fake.calls.first().authorization == "tok")
  }

  @Test
  fun `syncShipments cancels existing fulfillments and then reloads order before creating`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCancelOk(fulfillmentId = 8000L)
    fake.stubFulfillmentCreateOk(fulfillmentId = 9000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(9000L))
    assert(
      fake.calls.map { it.operationName } ==
        listOf(
          "GetOrderForDss",
          "FulfillmentCancelMutation",
          "GetOrderForDss",
          "FulfillmentCreateWithLineItems",
          "GetOrderForDss",
        ),
    )
  }

  @Test
  fun `syncShipments ignores already-canceled cancel errors as no-ops`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCancelUserError("Fulfillment is already canceled.")
    fake.stubFulfillmentCreateOk(fulfillmentId = 9001L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Ok<*>)
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

  @Test
  fun `syncShipments returns Network error when order reload fails after create`() = runBlocking {
    fake.enqueueGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.enqueueResponse("GetOrderForDss", """{"data":{"order":null}}""")
    fake.stubFulfillmentCreateOk(fulfillmentId = 5000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.Network)
    val message = (result as FulfillmentResult.Err.Network).message
    assert("fulfillmentId=5000 created but order reload failed" in message)
    assert("manual verify required" in message)
  }

  @Test
  fun `syncShipments resolves fulfillment id from gid when legacyResourceId is missing`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = CreatedFulfillment(
            id = "gid://shopify/Fulfillment/7777",
            legacyResourceId = "",
          ),
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(7777L))
  }

  @Test
  fun `dry-run failure does not call cancel`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest(quantity = 99))
    assert(result is FulfillmentResult.Err.UserError)
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
    assert(fake.calls.none { it.operationName == "FulfillmentCreateWithLineItems" })
    assert(fake.calls.single().operationName == "GetOrderForDss")
  }

  @Test
  fun `returns new_fulfillment_ids for each created shipment`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubSequence(
      "FulfillmentCreateWithLineItems",
      fulfillmentCreateOkJson(5001L),
      fulfillmentCreateOkJson(5002L),
    )
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
            shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
          ),
        ),
      )
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(5001L, 5002L))
  }

  @Test
  fun `reloads order between two shipments`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubSequence(
      "FulfillmentCreateWithLineItems",
      fulfillmentCreateOkJson(5001L),
      fulfillmentCreateOkJson(5002L),
    )
    syncShopifyShipmentsToFulfillments(
      shopify,
      syncRequest(
        shipments = listOf(
          shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
          shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
        ),
      ),
    )
    val getOrderCalls = fake.calls.filter { it.operationName == "GetOrderForDss" }
    assert(getOrderCalls.size == 3)
    val createIndices =
      fake.calls.mapIndexedNotNull { index, call ->
        if (call.operationName == "FulfillmentCreateWithLineItems") index else null
      }
    assert(createIndices.size == 2)
    val reloadBetweenCreates =
      fake.calls.withIndex().any { (index, call) ->
        call.operationName == "GetOrderForDss" &&
          index > createIndices.first() &&
          index < createIndices.last()
      }
    assert(reloadBetweenCreates)
  }

  @Test
  fun `skips create when all lines unmatched`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest(variantId = 999L))
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds.isEmpty())
    assert(fake.calls.none { it.operationName == "FulfillmentCreateWithLineItems" })
  }

  @Test
  fun `partial shipment still creates fulfillment for matched lines`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateOk(fulfillmentId = 5001L)
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            Shipment(
              trackingNumber = "TRK-MIXED",
              carrier = "UPS",
              trackingUrl = null,
              lineItems = listOf(
                ShipmentLineItem(productVariantId = 101L, quantity = 1),
                ShipmentLineItem(productVariantId = 999L, quantity = 1),
              ),
            ),
          ),
        ),
      )
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(5001L))
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 1)
  }

  @Test
  fun `diagram cross-FO shipment creates one fulfillment spanning two fulfillment orders`() = runBlocking {
    fake.stubGetOrderForDss(order = diagramCrossFoOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateOk(fulfillmentId = 5100L)
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        SyncShipmentsWithFulfillmentsRequest(
          shopifySubdomain = "acme",
          shopifyOrderId = 1001L,
          shipments = listOf(diagramCrossFoShipment()),
        ),
      )
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(5100L))
    val createCall = fake.calls.single { it.operationName == "FulfillmentCreateWithLineItems" }
    val foIds = fulfillmentOrderIdsFromCreateCall(createCall)
    assert(foIds.size == 2)
    assert("gid://shopify/FulfillmentOrder/301" in foIds)
    assert("gid://shopify/FulfillmentOrder/302" in foIds)
    val tracking = createCall.variables.jsonObject["tracking"]?.jsonObject
    assert(tracking?.get("number")?.jsonPrimitive?.content == "TRK-A")
  }

  @Test
  fun `returns UserError when fulfillment missing in create response`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubData(
      "FulfillmentCreateWithLineItems",
      FulfillmentCreateWithLineItems.Result(
        fulfillmentCreate = FulfillmentCreatePayload(
          fulfillment = null,
          userErrors = emptyList(),
        ),
      ),
      FulfillmentCreateWithLineItems.Result.serializer(),
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result is FulfillmentResult.Err.UserError)
    assert("fulfillment missing in response" in (result as FulfillmentResult.Err.UserError).messages.single())
  }

  @Test
  fun `mixed shipments skips all-unmatched shipment and creates for matched shipment`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateOk(fulfillmentId = 5200L)
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            shipment(tracking = "TRK-BAD", variantId = 999L, quantity = 1),
            shipment(tracking = "TRK-GOOD", variantId = 101L, quantity = 1),
          ),
        ),
      )
    val response = result.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(5200L))
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 1)
    val createCall = fake.calls.single { it.operationName == "FulfillmentCreateWithLineItems" }
    assert(createCall.variables.jsonObject["tracking"]?.jsonObject?.get("number")?.jsonPrimitive?.content == "TRK-GOOD")
  }

  @Test
  fun `partial failure recovery on retry cancels partial and recreates all`() = runBlocking {
    val order = minimalOrder().copy(fulfillments = emptyList())
    fake.stubGetOrderForDss(order = order)
    fake.stubSequence(
      "FulfillmentCreateWithLineItems",
      fulfillmentCreateOkJson(5001L),
      fulfillmentCreateUserErrorJson("tracking number invalid"),
    )
    val firstAttempt =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
            shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
          ),
        ),
      )
    assert(firstAttempt is FulfillmentResult.Err.UserError)
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 2)

    fake.reset()
    val orderWithPartial =
      order.copy(
        fulfillments = listOf(
          Fulfillment(
            id = "gid://shopify/Fulfillment/5001",
            legacyResourceId = "5001",
            trackingInfo = emptyList(),
          ),
        ),
      )
    fake.stubGetOrderForDss(order = orderWithPartial)
    fake.stubFulfillmentCancelOk(fulfillmentId = 5001L)
    fake.stubSequence(
      "FulfillmentCreateWithLineItems",
      fulfillmentCreateOkJson(6001L),
      fulfillmentCreateOkJson(6002L),
    )
    val retry =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
            shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
          ),
        ),
      )
    val response = retry.unwrapOk<SyncShipmentsWithFulfillmentsResponse>()
    assert(response.newFulfillmentIds == listOf(6001L, 6002L))
    assert(
      fake.calls.map { it.operationName } ==
        listOf(
          "GetOrderForDss",
          "FulfillmentCancelMutation",
          "GetOrderForDss",
          "FulfillmentCreateWithLineItems",
          "GetOrderForDss",
          "FulfillmentCreateWithLineItems",
          "GetOrderForDss",
        ),
    )
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

  private fun FakeShopifyGraphqlServer.enqueueGetOrderForDss(
    order: dropnext.graphql.generated.getorderfordss.Order?,
  ) {
    val payload = GetOrderForDss.Result(order = order)
    val dataJson = orderJson.encodeToJsonElement(GetOrderForDss.Result.serializer(), payload)
    val response = buildJsonObject {
      put("data", dataJson)
    }
    enqueueResponse("GetOrderForDss", response.toString())
  }

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

  private fun syncRequest(
    variantId: Long = 101L,
    quantity: Int = 1,
    shipments: List<Shipment>? = null,
  ): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = shipments ?: listOf(shipment(variantId = variantId, quantity = quantity)),
    )

  private fun shipment(variantId: Long, quantity: Int, tracking: String = "1Z999"): Shipment =
    Shipment(
      trackingNumber = tracking,
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(productVariantId = variantId, quantity = quantity)),
    )

  private fun fulfillmentCreateOkJson(fulfillmentId: Long): String =
    """{"data":{"fulfillmentCreate":{"fulfillment":{"id":"gid://shopify/Fulfillment/$fulfillmentId","legacyResourceId":"$fulfillmentId"},"userErrors":[]}}}"""

  private fun fulfillmentCreateUserErrorJson(message: String): String =
    """{"data":{"fulfillmentCreate":{"fulfillment":null,"userErrors":[{"field":["tracking"],"message":"$message"}]}}}"""

  private fun fulfillmentOrderIdsFromCreateCall(
    call: FakeShopifyGraphqlServer.RecordedCall,
  ): List<String> {
    val lineItemsByFo =
      call.variables.jsonObject["lineItemsByFulfillmentOrder"]?.jsonArray
        ?: return emptyList()
    return lineItemsByFo.map { entry ->
      entry.jsonObject["fulfillmentOrderId"]!!.jsonPrimitive.content
    }
  }
}
