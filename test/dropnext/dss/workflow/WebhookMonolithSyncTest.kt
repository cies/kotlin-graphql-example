package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.OrderLineItem
import dropnext.dss.lib.dss.dto.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.testing.fake.FakeMonolithService
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class WebhookMonolithSyncTest {

  @Test
  fun `posts mapped order via FakeMonolithService`() {
    val fake = FakeMonolithService()
    val req = sampleCreateOrderRequest()
    val result = kotlinx.coroutines.runBlocking {
      postMappedOrderToMonolith(fake, req, "orders/create")
    }
    assert(result is CreateOrderResult.HttpResponseSummary)
    assert(fake.createOrderCallCount == 1)
    assert(fake.lastCreateOrder?.shopifySubdomain == "dropnext-staging")
    assert(fake.lastCreateOrder?.shopifyOrderId == 1001L)
  }

  @Test
  fun `syncShopifyOrderToMonolith loads via Graphql and forwards to monolith`() {
    val gql = FakeShopifyGraphqlServer()
    val port = gql.start()
    val httpClient = HttpClient(OkHttp) {
      engine { config { connectTimeout(2, TimeUnit.SECONDS); readTimeout(5, TimeUnit.SECONDS) } }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
    }
    try {
      val client = GraphQLKtorClient(URI("http://localhost:$port/admin/api/2026-04/graphql.json").toURL(), httpClient)
      gql.stubData(
        "GetOrderForDss",
        GetOrderForDss.Result(order = minimalOrder()),
        GetOrderForDss.Result.serializer(),
      )
      val monolith = FakeMonolithService()
      val result = runBlocking {
        syncShopifyOrderToMonolith(
          gqlClient = client,
          token = "shpat_test",
          shopMyShopifyHost = "acme.myshopify.com",
          monolith = monolith,
          orderGid = "gid://shopify/Order/1001",
          webhookTopic = "orders/create",
        )
      }
      assert(result is CreateOrderResult.HttpResponseSummary)
      assert((result as CreateOrderResult.HttpResponseSummary).status == 200)
      assert(monolith.createOrderCallCount == 1)
      assert(monolith.lastCreateOrder?.shopifyOrderId == 1001L)
      assert(monolith.lastCreateOrder?.shopifySubdomain == "acme")
      assert(gql.calls.single().authorization == "shpat_test")
    } finally {
      httpClient.close()
      gql.stop()
    }
  }

  @Test
  fun `syncShopifyOrderToMonolith returns null when order not found`() {
    val gql = FakeShopifyGraphqlServer()
    val port = gql.start()
    val httpClient = HttpClient(OkHttp) {
      install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }
    try {
      val client = GraphQLKtorClient(URI("http://localhost:$port/admin/api/2026-04/graphql.json").toURL(), httpClient)
      gql.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
      val monolith = FakeMonolithService()
      val result = runBlocking {
        syncShopifyOrderToMonolith(client, "tok", "acme.myshopify.com", monolith, "gid://shopify/Order/1001", "orders/create")
      }
      assert(result == null)
      assert(monolith.createOrderCallCount == 0)
    } finally {
      httpClient.close()
      gql.stop()
    }
  }

  @Test
  fun `syncShopifyOrderToMonolith returns null when no mapped line items remain`() {
    val gql = FakeShopifyGraphqlServer()
    val port = gql.start()
    val httpClient = HttpClient(OkHttp) {
      install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }
    try {
      val client = GraphQLKtorClient(URI("http://localhost:$port/admin/api/2026-04/graphql.json").toURL(), httpClient)
      // Order with no fulfillment orders → mapped lineItems is empty → skip sync.
      val orderWithoutFOs = minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList()))
      gql.stubData("GetOrderForDss", GetOrderForDss.Result(order = orderWithoutFOs), GetOrderForDss.Result.serializer())
      val monolith = FakeMonolithService()
      val result = runBlocking {
        syncShopifyOrderToMonolith(client, "tok", "acme.myshopify.com", monolith, "gid://shopify/Order/1001", "orders/create")
      }
      assert(result == null)
      assert(monolith.createOrderCallCount == 0)
    } finally {
      httpClient.close()
      gql.stop()
    }
  }

  @Test
  fun `surfaces monolith 500 as Error with parsed trace`() {
    val fake =
      FakeMonolithService().apply {
        createOrderStatus = 500
        createOrderErrorBody =
          """{"error":{"code":"InternalError","message":"fail","trace_id":"fake123"}}"""
      }
    val result =
      kotlinx.coroutines.runBlocking {
        postMappedOrderToMonolith(fake, sampleCreateOrderRequest(), "orders/create")
      }
    assert(result is CreateOrderResult.Error)
    val err = result as CreateOrderResult.Error
    assert(err.parsed?.monolithTraceId == "fake123")
  }

  private fun sampleCreateOrderRequest(): CreateShopifyOrderRequest =
    CreateShopifyOrderRequest(
      shopifySubdomain = "dropnext-staging",
      shopifyOrderId = 1001L,
      name = "#1001",
      financialStatus = "paid",
      fulfillmentStatus = null,
      createdAt = "2026-04-25T10:30:00Z",
      shippingAddress =
        ShippingAddress(
          firstName = null,
          lastName = null,
          address1 = "",
          address2 = null,
          city = "",
          province = null,
          provinceCode = null,
          countryCode = "US",
          zip = null,
          phone = null,
        ),
      lineItems =
        listOf(
          OrderLineItem(
            shopifyLineItemId = 201L,
            productVariantId = 101L,
            quantity = 1,
            fulfillmentOrderId = 0L,
            snapshotOfVariantTitle = "Item",
            snapshotOfProductTitle = "Product",
            snapshotOfPriceInMinorUnits = 1999L,
          ),
        ),
      totalInMinorUnits = 1999L,
      currency = "USD",
    )
}
