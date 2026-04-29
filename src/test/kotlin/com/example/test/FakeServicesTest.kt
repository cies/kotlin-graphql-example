package com.example.test

import com.example.createSharedHttpClient
import com.example.lib.dss.dto.CreateShopifyOrderRequest
import com.example.lib.dss.dto.ShippingAddress
import com.example.lib.monolith.HttpMonolithClient
import com.example.testing.fake.FakeMonolithConfig
import com.example.testing.fake.FakeMonolithService
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the outbound monolith client against [FakeMonolithService] (fake HTTP server, not mocks).
 */
class FakeServicesTest {
  @Test
  fun `httpMonolithClient posts create order to fakeMonolithClient and succeeds`() =
    runBlocking {
      val fakeMonolithClient = FakeMonolithService()
      fakeMonolithClient.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithClient =
            HttpMonolithClient(
              httpClient = httpClient,
              baseUrl = fakeMonolithClient.baseUrl,
              apiKey = null,
              createOrderPath = "/orders",
            )
          val req = minimalCreateOrder(shopifyOrderId = 9001L)
          val r = httpMonolithClient.postCreateOrder(req)
          logFakeExchange(fakeMonolithClient, r)
          assertTrue(r.isSuccess, r.toString())
          assertEquals(1, fakeMonolithClient.ordersPostCount.get())
        }
      } finally {
        fakeMonolithClient.stop()
      }
    }

  @Test
  fun `httpMonolithClient receives failure when fake returns 409`() =
    runBlocking {
      val fakeMonolithClient =
        FakeMonolithService(
          initialConfig = FakeMonolithConfig(ordersResponseStatus = HttpStatusCode.Conflict),
        )
      fakeMonolithClient.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithClient =
            HttpMonolithClient(
              httpClient = httpClient,
              baseUrl = fakeMonolithClient.baseUrl,
              apiKey = null,
              createOrderPath = "/orders",
            )
          val r = httpMonolithClient.postCreateOrder(minimalCreateOrder(1L))
          logFakeExchange(fakeMonolithClient, r)
          assertTrue(r.isFailure)
        }
      } finally {
        fakeMonolithClient.stop()
      }
    }

  @Test
  fun `httpMonolithClient sends bearer when api key set`() =
    runBlocking {
      val fakeMonolithClient =
        FakeMonolithService(
          initialConfig = FakeMonolithConfig(requiredBearerToken = "secret-test-token"),
        )
      fakeMonolithClient.start()
      try {
        createSharedHttpClient().use { httpClient ->
          val httpMonolithClient =
            HttpMonolithClient(
              httpClient = httpClient,
              baseUrl = fakeMonolithClient.baseUrl,
              apiKey = "secret-test-token",
              createOrderPath = "/orders",
            )
          val r = httpMonolithClient.postCreateOrder(minimalCreateOrder(2L))
          logFakeExchange(fakeMonolithClient, r)
          assertTrue(r.isSuccess, r.toString())
          assertEquals("Bearer secret-test-token", fakeMonolithClient.lastAuthorizationHeader)
        }
      } finally {
        fakeMonolithClient.stop()
      }
    }

  private fun logFakeExchange(fake: FakeMonolithService, result: Result<*>) {
    if (System.getProperty("fakes.verbose") != "true") return
    println("--- FakeMonolithService ${fake.baseUrl} ---")
    println("Authorization seen by fake: ${fake.lastAuthorizationHeader}")
    println("POST body seen by fake:\n${fake.lastOrderJson}")
    println("HttpMonolithClient result: $result")
  }

  private fun minimalCreateOrder(shopifyOrderId: Long) =
    CreateShopifyOrderRequest(
      shopifySubdomain = "test-shop",
      shopifyOrderId = shopifyOrderId,
      name = "#1001",
      financialStatus = "paid",
      fulfillmentStatus = "UNFULFILLED",
      createdAt = "2026-01-01T12:00:00Z",
      shippingAddress =
        ShippingAddress(
          address1 = "1 Main St",
          city = "Austin",
          countryCode = "US",
        ),
      lineItems = emptyList(),
      totalInMinorUnits = 0L,
    )
}
