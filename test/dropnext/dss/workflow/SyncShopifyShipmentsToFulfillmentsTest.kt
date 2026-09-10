package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.config.Config
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.FulfillmentCreatePayload
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.UserError as CreateUserError
import dropnext.graphql.generated.getorderfordss.Fulfillment
import io.ktor.client.HttpClient
import java.net.URI
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.ResourceLock

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncShopifyShipmentsToFulfillmentsTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeAll
  fun startServer() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = testHttpClient()
    val url = URI(shopifyGraphqlUrl(port)).toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, ShopifyAdminToken("tok"))
  }

  @AfterAll
  fun stopServer() {
    httpClient.close()
    fake.stop()
  }

  @BeforeTest
  fun clearFakeBetweenTests() {
    fake.clear()
  }

  @Test
  fun `syncShipments returns NotFound when order is missing`() = runBlocking {
    fake.stubGetOrderForDss(order = null)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.failureOrNull() is ShopifyError.NotFound)
    assert("order 1001 not found" in result.failureOrNull()!!.message)
  }

  @Test
  fun `syncShipments succeeds when there are no existing fulfillments`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateOk(fulfillmentId = 5000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.newFulfillmentIds() == listOf(5000L))
    assert(
      fake.calls.map { it.operationName } ==
        listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"),
    )
    assert(fake.calls.first().authorization == "tok")
  }

  @Test
  fun `syncShipments creates without canceling existing fulfillments`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    fake.stubFulfillmentCreateOk(fulfillmentId = 9000L)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.newFulfillmentIds() == listOf(9000L))
    assert(
      fake.calls.map { it.operationName } ==
        listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"),
    )
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
  }

  @Test
  fun `syncShipments propagates create UserError`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubFulfillmentCreateUserError("tracking number invalid")
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.failureOrNull() is ShopifyError.UserError)
  }

  @Test
  fun `syncShipments treats Graphql errors on GetOrder as GraphqlError`() = runBlocking {
    fake.stubRaw(
      "GetOrderForDss",
      """{"data":{"order":null},"errors":[{"message":"throttled"}]}""",
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.failureOrNull() is ShopifyError.GraphqlError)
    assert("throttled" in result.failureOrNull()!!.message)
  }

  @Test
  fun `syncShipments returns GraphqlError when create-mutation response carries errors`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubRaw(
      "FulfillmentCreateWithLineItems",
      """{"data":{"fulfillmentCreate":{"fulfillment":null,"userErrors":[]}},"errors":[{"message":"throttled"}]}""",
    )
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.failureOrNull() is ShopifyError.GraphqlError)
    assert("throttled" in result.failureOrNull()!!.message)
  }

  @Test
  fun `syncShipments returns Network when initial loadOrder fails entirely (server down)`() = runBlocking {
    // A server that accepts and then resets, rather than a just-freed port that another test's
    // `port = 0` bind could claim between the close and the connect.
    FakeFlakyServer().use { unreachable ->
      val deadUrl = URI("${unreachable.baseUrl}/admin/api/${Config.DEFAULT_SHOPIFY_API_VERSION}/graphql.json").toURL()
      val deadShopify = HttpShopifyGraphqlService(
        ShopDomain.parse("acme.myshopify.com")!!,
        GraphQLKtorClient(deadUrl, httpClient),
        ShopifyAdminToken("tok"),
      )

      val result = syncShopifyShipmentsToFulfillments(deadShopify, syncRequest())
      assert(result.failureOrNull() is ShopifyError.Network)
    }
  }

  @Test
  fun `syncShipments returns Network error when a mutation call fails`() = runBlocking {
    fake.stubGetOrderForDss(order = orderWithFulfillment(id = 8000L))
    // Malformed JSON forces the graphql client to throw; the workflow maps that to Network.
    fake.stubRaw("FulfillmentCreateWithLineItems", "{not-valid-json")
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.failureOrNull() is ShopifyError.Network)
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
    assert(result.newFulfillmentIds() == listOf(7777L))
  }

  @Test
  fun `dry-run failure does not call create`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest(quantity = 99))
    assert(result.failureOrNull() is ShopifyError.UserError)
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
    assert(result.newFulfillmentIds() == listOf(5001L, 5002L))
  }

  /** The one line an operator searches for: it must name the order, the shop, the counts and the ids. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a successful run logs one summary line with the counts and the created ids`() {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    fake.stubSequence(
      "FulfillmentCreateWithLineItems",
      fulfillmentCreateOkJson(5001L),
      fulfillmentCreateOkJson(5002L),
    )
    val lines = capturingLogs {
      runBlocking {
        syncShopifyShipmentsToFulfillments(
          shopify,
          syncRequest(
            shipments = listOf(
              shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
              shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
            ),
          ),
        )
      }
    }
    val line = lines.single { "sync-shipments " in it }
    assert(line.startsWith("INFO "))
    assert("orderId=1001" in line)
    assert("shop=acme" in line)
    assert("canceled=0" in line)
    assert("created=2" in line)
    assert("skippedShipments=0" in line)
    assert("fulfillmentIds=[5001, 5002]" in line)
  }

  @Test
  fun `does not reload the order between two shipment creates`() = runBlocking {
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
    assert(getOrderCalls.size == 1)
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 2)
  }

  @Test
  fun `skips create when all lines unmatched`() = runBlocking {
    fake.stubGetOrderForDss(order = minimalOrder().copy(fulfillments = emptyList()))
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest(variantId = 999L))
    assert(result.newFulfillmentIds().isEmpty())
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
    assert(result.newFulfillmentIds() == listOf(5001L))
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
    assert(result.newFulfillmentIds() == listOf(5100L))
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
    assert(result.failureOrNull() is ShopifyError.UserError)
    assert("fulfillment missing in response" in (result.failureOrNull() as ShopifyError.UserError).messages.single())
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
    assert(result.newFulfillmentIds() == listOf(5200L))
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 1)
    val createCall = fake.calls.single { it.operationName == "FulfillmentCreateWithLineItems" }
    assert(createCall.variables.jsonObject["tracking"]?.jsonObject?.get("number")?.jsonPrimitive?.content == "TRK-GOOD")
  }

  @Test
  fun `partial failure recovery on retry skips fulfilled variant and creates the rest`() = runBlocking {
    val order = orderWithTwoVariantFulfillmentOrders()
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
            shipment(tracking = "TRK-2", variantId = 202L, quantity = 1),
          ),
        ),
      )
    assert(firstAttempt.failureOrNull() is ShopifyError.UserError)
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 2)

    fake.clear()
    val orderAfterPartial =
      orderWithTwoVariantFulfillmentOrders(
        firstRemaining = 0,
        firstTotal = 1,
        secondRemaining = 1,
        secondTotal = 1,
      ).copy(
        fulfillments = listOf(
          Fulfillment(
            id = "gid://shopify/Fulfillment/5001",
            legacyResourceId = "5001",
            trackingInfo = emptyList(),
          ),
        ),
      )
    fake.stubGetOrderForDss(order = orderAfterPartial)
    fake.stubFulfillmentCreateOk(fulfillmentId = 6002L)
    val retry =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(
          shipments = listOf(
            shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
            shipment(tracking = "TRK-2", variantId = 202L, quantity = 1),
          ),
        ),
      )
    assert(retry.newFulfillmentIds() == listOf(6002L))
    assert(
      fake.calls.map { it.operationName } ==
        listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"),
    )
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
  }

  @Test
  fun `remaining quantity creates without canceling existing fulfillments`() = runBlocking {
    val order =
      orderWithFoQuantities(remaining = 1, total = 2).copy(
        fulfillments = listOf(
          Fulfillment(
            id = "gid://shopify/Fulfillment/8000",
            legacyResourceId = "8000",
            trackingInfo = emptyList(),
          ),
        ),
      )
    fake.stubGetOrderForDss(order = order)
    fake.stubFulfillmentCreateOk(fulfillmentId = 9001L)
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(shipments = listOf(shipment(tracking = "TRK-2", variantId = 101L, quantity = 1))),
      )
    assert(result.newFulfillmentIds() == listOf(9001L))
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
    assert(fake.calls.count { it.operationName == "FulfillmentCreateWithLineItems" } == 1)
  }

  @Test
  fun `second item later creates without canceling the first fulfillment`() = runBlocking {
    val order = orderWithTwoVariantFulfillmentOrders(
      firstRemaining = 0,
      firstTotal = 1,
      secondRemaining = 1,
      secondTotal = 1,
    ).copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/8000",
          legacyResourceId = "8000",
          trackingInfo = emptyList(),
        ),
      ),
    )
    fake.stubGetOrderForDss(order = order)
    fake.stubFulfillmentCreateOk(fulfillmentId = 9002L)
    val result =
      syncShopifyShipmentsToFulfillments(
        shopify,
        syncRequest(shipments = listOf(shipment(tracking = "TRK-O2", variantId = 202L, quantity = 1))),
      )
    assert(result.newFulfillmentIds() == listOf(9002L))
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
    assert(fake.calls.map { it.operationName } == listOf("GetOrderForDss", "FulfillmentCreateWithLineItems"))
  }

  @Test
  fun `already fulfilled same variant is skipped with no cancel`() = runBlocking {
    val order = orderWithFoQuantities(remaining = 0, total = 1).copy(
      fulfillments = listOf(
        Fulfillment(
          id = "gid://shopify/Fulfillment/8000",
          legacyResourceId = "8000",
          trackingInfo = emptyList(),
        ),
      ),
    )
    fake.stubGetOrderForDss(order = order)
    val result = syncShopifyShipmentsToFulfillments(shopify, syncRequest())
    assert(result.newFulfillmentIds().isEmpty())
    assert(fake.calls.none { it.operationName == "FulfillmentCancelMutation" })
    assert(fake.calls.none { it.operationName == "FulfillmentCreateWithLineItems" })
  }

  // ---------- helpers ----------

  /** The created ids as the wire carries them, asserting the result was a success on the way. */
  private fun Result<List<ShopifyFulfillmentId>, ShopifyError>.newFulfillmentIds(): List<Long> {
    assert(this is Success)
    return (this as Success).value.map { it.value }
  }

  private fun Result<List<ShopifyFulfillmentId>, ShopifyError>.failureOrNull(): ShopifyError? =
    (this as? Failure)?.reason

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
