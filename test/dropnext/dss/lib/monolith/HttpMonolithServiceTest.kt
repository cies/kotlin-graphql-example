package dropnext.dss.lib.monolith

import dropnext.dss.lib.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dto.ProductStatus
import dropnext.dss.lib.dto.ProductVariantItem
import dropnext.dss.lib.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.json.AppJson
import dropnext.dss.testing.fake.FakeMonolithHttpServer
import dropnext.dss.workflow.minimalOrder
import dropnext.dss.workflow.orderToCreateShopifyOrderRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking

class HttpMonolithServiceTest {

  private lateinit var server: FakeMonolithHttpServer
  private lateinit var httpClient: HttpClient
  private lateinit var baseUrl: String

  @BeforeTest
  fun setUp() {
    server = FakeMonolithHttpServer()
    val port = server.start()
    baseUrl = "http://localhost:$port"
    httpClient = HttpClient(OkHttp) {
      engine {
        config {
          connectTimeout(2, TimeUnit.SECONDS)
          readTimeout(5, TimeUnit.SECONDS)
          writeTimeout(5, TimeUnit.SECONDS)
        }
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
      install(ContentNegotiation) { json(AppJson) }
    }
  }

  @AfterTest
  fun tearDown() {
    httpClient.close()
    server.stop()
  }

  private fun service(
    apiPrefix: String? = null,
    apiKey: String? = null,
    createOrderPath: String = "/orders",
  ): HttpMonolithService =
    HttpMonolithService(
      httpClient = httpClient,
      baseUrl = baseUrl,
      apiPathPrefix = apiPrefix,
      apiKey = apiKey,
      createOrderPath = createOrderPath,
    )

  // ---------- postCreateOrder ----------

  @org.junit.jupiter.api.Test
  fun `postCreateOrder returns HttpResponseSummary on 200`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"shopify_order_id":1001}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = service().postCreateOrder(req)
    assert(result is CreateOrderResult.HttpResponseSummary)
    result as CreateOrderResult.HttpResponseSummary
    assert(result.status == 200)
    assert("shopify_order_id" in result.body)
    val recorded = server.requests.single()
    assert(recorded.method == "POST")
    assert(recorded.path == "/orders")
    assert(recorded.contentType()?.startsWith("application/json") == true)
  }

  @org.junit.jupiter.api.Test
  fun `postCreateOrder treats 409 as HttpResponseSummary (idempotent duplicate)`() = runBlocking {
    server.enqueue(HttpStatusCode.Conflict, """{"error":"Order already exists."}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = service().postCreateOrder(req)
    assert(result is CreateOrderResult.HttpResponseSummary)
    assert((result as CreateOrderResult.HttpResponseSummary).status == 409)
  }

  @org.junit.jupiter.api.Test
  fun `postCreateOrder maps non-2xx, non-409 to Error with parsed body`() = runBlocking {
    server.enqueue(HttpStatusCode.BadRequest, """{"error":{"code":"InvalidOrder","message":"bad","trace_id":"t-1"}}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = service().postCreateOrder(req)
    assert(result is CreateOrderResult.Error)
    result as CreateOrderResult.Error
    assert(result.status == 400)
    assert(result.errorMessage == "bad")
    assert(result.parsed?.monolithTraceId == "t-1")
  }

  @org.junit.jupiter.api.Test
  fun `postCreateOrder returns Error(0) on connection failure`() = runBlocking {
    // Acquire a port by binding briefly, then close — the just-freed port is reliably refused.
    val transient = FakeMonolithHttpServer()
    val deadPort = transient.start()
    transient.stop()
    val deadService =
      HttpMonolithService(httpClient, "http://localhost:$deadPort", null, null)
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = deadService.postCreateOrder(req)
    assert(result is CreateOrderResult.Error)
    assert((result as CreateOrderResult.Error).status == 0)
  }

  // ---------- putStoreApiKey ----------

  @org.junit.jupiter.api.Test
  fun `putStoreApiKey on 200 deserialises the storeId`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"store_id":42}""")
    val result =
      service().putStoreApiKey(
        UpdateStoreApiKeyRequest(shopifySubdomain = "acme", shopifyShopId = 99L, apiKey = "shpat_x"),
      )
    assert(result is StoreApiKeyResult.Ok)
    assert((result as StoreApiKeyResult.Ok).storeId == 42L)
    val recorded = server.requests.single()
    assert(recorded.method == "PUT")
    assert(recorded.path == "/stores/api-key")
    assert("\"api_key\":\"shpat_x\"" in recorded.body)
  }

  @org.junit.jupiter.api.Test
  fun `putStoreApiKey on 500 returns Error`() = runBlocking {
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
    val result =
      service().putStoreApiKey(UpdateStoreApiKeyRequest("acme", 99L, "shpat_x"))
    assert(result is StoreApiKeyResult.Error)
    assert((result as StoreApiKeyResult.Error).status == 500)
  }

  // ---------- getStore ----------

  @org.junit.jupiter.api.Test
  fun `getStore on 200 deserialises the StoreResponse`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":"shpat_x"}""")
    val result = service().getStore("acme")
    assert(result is GetStoreResult.Ok)
    result as GetStoreResult.Ok
    assert(result.storeId == 1L)
    assert(result.shopifyShopId == 99L)
    assert(result.apiKey == "shpat_x")
    val recorded = server.requests.single()
    assert(recorded.method == "GET")
    assert(recorded.path == "/stores")
    assert(recorded.query["shopify_subdomain"] == listOf("acme"))
  }

  @org.junit.jupiter.api.Test
  fun `getStore on 404 returns NotFound with the subdomain`() = runBlocking {
    server.enqueue(HttpStatusCode.NotFound, """{"error":"missing"}""")
    val result = service().getStore("acme")
    assert(result is GetStoreResult.NotFound)
    assert((result as GetStoreResult.NotFound).shopifySubdomain == "acme")
  }

  @org.junit.jupiter.api.Test
  fun `getStore on 500 returns Error`() = runBlocking {
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"boom"}""")
    val result = service().getStore("acme")
    assert(result is GetStoreResult.Error)
    assert((result as GetStoreResult.Error).status == 500)
  }

  // ---------- upsertProductVariants ----------

  @org.junit.jupiter.api.Test
  fun `upsertProductVariants on 200 returns Ok with upserted count`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"upserted":3}""")
    val request =
      UpsertProductVariantsRequest(shopifySubdomain = "acme", productVariants = listOf(sampleVariant()))
    val result = service().upsertProductVariants(request)
    assert(result is UpsertVariantsResult.Ok)
    assert((result as UpsertVariantsResult.Ok).upserted == 3)
    val recorded = server.requests.single()
    assert(recorded.method == "POST")
    assert(recorded.path == "/product-variants")
  }

  @org.junit.jupiter.api.Test
  fun `upsertProductVariants on 502 returns Error`() = runBlocking {
    server.enqueue(HttpStatusCode.BadGateway, "")
    val result =
      service().upsertProductVariants(
        UpsertProductVariantsRequest("acme", listOf(sampleVariant())),
      )
    assert(result is UpsertVariantsResult.Error)
    assert((result as UpsertVariantsResult.Error).status == 502)
  }

  // ---------- getProductVariantIds ----------

  @org.junit.jupiter.api.Test
  fun `getProductVariantIds on 200 returns Ok with id list`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"product_variant_ids":[1,2,3]}""")
    val result = service().getProductVariantIds("acme")
    assert(result is GetVariantIdsResult.Ok)
    assert((result as GetVariantIdsResult.Ok).productVariantIds == listOf(1L, 2L, 3L))
    val recorded = server.requests.single()
    assert(recorded.method == "GET")
    assert(recorded.query["shopify_subdomain"] == listOf("acme"))
  }

  @org.junit.jupiter.api.Test
  fun `getProductVariantIds on 404 returns NotFound`() = runBlocking {
    server.enqueue(HttpStatusCode.NotFound, "")
    val result = service().getProductVariantIds("acme")
    assert(result is GetVariantIdsResult.NotFound)
  }

  // ---------- deleteProductVariants ----------

  @org.junit.jupiter.api.Test
  fun `deleteProductVariants on 200 returns Ok with deleted count`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"deleted":2}""")
    val result =
      service().deleteProductVariants(
        DeleteProductVariantsRequest(shopifySubdomain = "acme", productVariantIds = listOf(11L, 22L)),
      )
    assert(result is DeleteVariantsResult.Ok)
    assert((result as DeleteVariantsResult.Ok).deleted == 2)
    val recorded = server.requests.single()
    assert(recorded.method == "DELETE")
    assert(recorded.path == "/product-variants")
    assert("\"product_variant_ids\":[11,22]" in recorded.body)
  }

  @org.junit.jupiter.api.Test
  fun `deleteProductVariants on 500 returns Error`() = runBlocking {
    server.enqueue(HttpStatusCode.InternalServerError, """{"error":"nope"}""")
    val result =
      service().deleteProductVariants(
        DeleteProductVariantsRequest("acme", listOf(1L)),
      )
    assert(result is DeleteVariantsResult.Error)
    assert((result as DeleteVariantsResult.Error).status == 500)
  }

  // ---------- MonolithErrorBody integration ----------

  @org.junit.jupiter.api.Test
  fun `monolith trace_id is parsed and propagated through CreateOrderResult Error`() = runBlocking {
    server.enqueue(
      HttpStatusCode.InternalServerError,
      """{"error":{"code":"InternalError","message":"backend boom","trace_id":"mt-9bf3"}}""",
    )
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    val result = service().postCreateOrder(req)
    assert(result is CreateOrderResult.Error)
    result as CreateOrderResult.Error
    assert(result.status == 500)
    assert(result.errorMessage == "backend boom")
    assert(result.parsed?.monolithTraceId == "mt-9bf3")
    assert(result.parsed?.code == "InternalError")
  }

  @org.junit.jupiter.api.Test
  fun `monolith trace_id is parsed for getStore errors too`() = runBlocking {
    server.enqueue(
      HttpStatusCode.InternalServerError,
      """{"error":{"code":"DbDown","message":"unavailable","traceId":"mt-camel"}}""",
    )
    val result = service().getStore("acme")
    assert(result is GetStoreResult.Error)
    result as GetStoreResult.Error
    assert(result.parsed?.monolithTraceId == "mt-camel")
  }

  // ---------- url composition & auth header ----------

  @org.junit.jupiter.api.Test
  fun `apiPathPrefix is inserted between baseUrl and path`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    service(apiPrefix = "api/v1").getStore("acme")
    val recorded = server.requests.single()
    assert(recorded.path == "/api/v1/stores")
  }

  @org.junit.jupiter.api.Test
  fun `apiPathPrefix with surrounding slashes is normalised`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
    service(apiPrefix = "/api/v1/").getStore("acme")
    val recorded = server.requests.single()
    assert(recorded.path == "/api/v1/stores")
  }

  @org.junit.jupiter.api.Test
  fun `apiKey header behavior — null, blank, and non-blank`() = runBlocking {
    val cases = listOf<Pair<String?, String?>>(
      null to null,
      "" to null,
      "   " to null,
      "key-abc" to "Bearer key-abc",
    )
    cases.forEach { (apiKey, expected) ->
      server.reset()
      server.enqueue(HttpStatusCode.OK, """{"store_id":1,"shopify_shop_id":99,"api_key":null}""")
      service(apiKey = apiKey).getStore("acme")
      val actual = server.requests.single().authorization()
      assert(actual == expected) {
        "apiKey=$apiKey expected Authorization=$expected but got $actual"
      }
    }
  }

  @org.junit.jupiter.api.Test
  fun `custom createOrderPath is honoured`() = runBlocking {
    server.enqueue(HttpStatusCode.OK, """{"shopify_order_id":1001}""")
    val req = orderToCreateShopifyOrderRequest("acme", minimalOrder())
    service(createOrderPath = "/custom/orders").postCreateOrder(req)
    assert(server.requests.single().path == "/custom/orders")
  }

  private fun sampleVariant(): ProductVariantItem =
    ProductVariantItem(
      productVariantId = 1L,
      productId = 10L,
      productTitle = "T-Shirt",
      productDescription = "",
      productDescriptionHtml = "",
      productVendor = "",
      productType = "",
      productTags = emptyList(),
      productHandle = "t-shirt",
      productStatus = ProductStatus.ACTIVE,
      productImages = emptyList(),
      productCreatedAt = "2026-04-01T00:00:00Z",
      productUpdatedAt = "2026-04-01T00:00:00Z",
      title = "Default",
      priceInMinorUnits = 1995L,
      priceCurrency = "USD",
      selectedOptions = emptyList(),
    )
}
