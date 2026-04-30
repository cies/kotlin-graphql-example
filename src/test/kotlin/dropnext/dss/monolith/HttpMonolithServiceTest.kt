package dropnext.dss.monolith

import dropnext.dss.createSharedHttpClient
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.ShippingAddress
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.HttpMonolithService
import io.ktor.http.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the outbound monolith client against [FakeMonolithService] (fake HTTP server, not mocks).
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
          assertTrue(response is CreateOrderResult.HttpResponseSummary, response.toString())
          assertEquals(1, fakeMonolithHttpEndpoint.ordersPostCount.get())
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
          assertTrue(response is CreateOrderResult.Error)
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
          assertTrue(response is CreateOrderResult.HttpResponseSummary, response.toString())
          assertEquals("Bearer secret-test-token", fakeMonolithHttpEndpoint.lastAuthorizationHeader)
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
    )
}
