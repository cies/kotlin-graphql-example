package dropnext.dss.monolith

import dropnext.dss.createSharedHttpClient
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.HttpMonolithService
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the outbound monolith client against [FakeMonolithHttpEndpoint] (fake HTTP server, not mocks).
 */
class HttpMonolithServiceTest {
  @Test
  fun `httpMonolithClient posts create order to fakeMonolithClient and succeeds`() =
    runBlocking {
      val fakeMonolithHttpEndpoint = FakeMonolithHttpEndpoint()
      fakeMonolithHttpEndpoint.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithService = HttpMonolithService(
            httpClient = httpClient,
            baseUrl = fakeMonolithHttpEndpoint.baseUrl,
            apiKey = null,
          )
          val req = minimalCreateOrder(shopifyOrderId = 9001L)
          val response = httpMonolithService.postCreateOrder(req)
          assert(response is CreateOrderResult.HttpResponseSummary) { "Expected success but got: $response" }
          assert(fakeMonolithHttpEndpoint.ordersPostCount.get() == 1)
        }
      } finally {
        fakeMonolithHttpEndpoint.stop()
      }
    }

  @Test
  fun `httpMonolithClient receives failure when fake returns 409`() =
    runBlocking {
      val fakeMonolithHttpEndpoint = FakeMonolithHttpEndpoint(
        initialConfig = FakeMonolithConfig(ordersResponseStatus = HttpStatusCode.Conflict),
      )
      fakeMonolithHttpEndpoint.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithService = HttpMonolithService(
            httpClient = httpClient,
            baseUrl = fakeMonolithHttpEndpoint.baseUrl,
            apiKey = null,
          )
          val response = httpMonolithService.postCreateOrder(minimalCreateOrder(1L))
          assert(response is CreateOrderResult.Error) { "Expected error but got: $response" }
        }
      } finally {
        fakeMonolithHttpEndpoint.stop()
      }
    }

  @Test
  fun `httpMonolithClient sends bearer when api key set`() =
    runBlocking {
      val fakeMonolithHttpEndpoint = FakeMonolithHttpEndpoint(
        initialConfig = FakeMonolithConfig(requiredBearerToken = "secret-test-token"),
      )
      fakeMonolithHttpEndpoint.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithService = HttpMonolithService(
            httpClient = httpClient,
            baseUrl = fakeMonolithHttpEndpoint.baseUrl,
            apiKey = "secret-test-token",
          )
          val response = httpMonolithService.postCreateOrder(minimalCreateOrder(2L))
          assert(response is CreateOrderResult.HttpResponseSummary) { "Expected success but got: $response" }
          assert(fakeMonolithHttpEndpoint.lastAuthorizationHeader == "Bearer secret-test-token")
        }
      } finally {
        fakeMonolithHttpEndpoint.stop()
      }
    }

  private fun minimalCreateOrder(shopifyOrderId: Long) =
    CreateShopifyOrderRequest(
      shopifySubdomain = "test-shop",
      shopifyOrderId = shopifyOrderId,
      name = "#1001",
      financialStatus = "paid",
      fulfillmentStatus = "UNFULFILLED",
      createdAt = "2026-01-01T12:00:00Z",
      shippingAddress = ShippingAddress(
        address1 = "1 Main St",
        city = "Austin",
        countryCode = "US",
      ),
      lineItems = emptyList(),
      totalInMinorUnits = 0L,
      currency = "USD",
    )
}
