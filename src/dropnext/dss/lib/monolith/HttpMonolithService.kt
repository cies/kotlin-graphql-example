package dropnext.dss.lib.monolith

import dropnext.dss.path.MonolithPaths
import dropnext.dss.lib.json.MonolithJson
import dropnext.dss.lib.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dto.DeleteProductVariantsResponse
import dropnext.dss.lib.dto.StoreResponse
import dropnext.dss.lib.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dto.UpdateStoreApiKeyResponse
import dropnext.dss.lib.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.dto.UpsertProductVariantsResponse
import dropnext.dss.lib.dto.VariantIdsResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.serialization.json.JsonObject

/**
 * Production [MonolithService]: real HTTP calls to the DropNext monolith over Ktor + OkHttp.
 *
 * URLs are built from `{baseUrl}/{apiPathPrefix}{path}`; both edges are trimmed of slashes
 * (see [prefixedBase]). Each method translates the HTTP outcome into a typed sealed result
 * (e.g. `Ok` / `NotFound` / `Error`) instead of throwing — handlers can then map errors to
 * the right log level and HTTP response without try/catch noise.
 *
 * Tests substitute the recording `FakeMonolithService`; see [docs/TESTING_WITH_FAKE_SERVICES.md].
 */
class HttpMonolithService(
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiPathPrefix: String?,
  private val apiKey: String?,
  private val createOrderPath: String = MonolithPaths.ORDERS,
) : MonolithService {

  private val createOrderJsonTopLevelKeys: Set<String> =
    setOf(
      "shopify_subdomain",
      "shopify_order_id",
      "name",
      "financial_status",
      "fulfillment_status",
      "created_at",
      "shipping_address",
      "line_items",
      "total_in_minor_units",
      "currency",
    )

  private val prefixedBase: String = run {
    val b = baseUrl.trimEnd('/')
    val p = apiPathPrefix?.trim()?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
    if (p == null) b else "$b/$p"
  }

  private fun url(path: String): String {
    val rel = path.trimStart('/')
    return "$prefixedBase/$rel"
  }

  private fun authHeader(block: HttpRequestBuilder) {
    if (!apiKey.isNullOrBlank()) {
      block.header("Authorization", "Bearer $apiKey")
    }
  }

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult {
    val payloadResult = encodeCreateOrderPayload(request)
    if (payloadResult is CreateOrderPayload.Invalid) {
      return CreateOrderResult.Error(0, payloadResult.reason, null)
    }
    val payload = (payloadResult as CreateOrderPayload.Ok).json
    return guardNetwork(onNetworkError = { CreateOrderResult.Error(0, it, null) }) {
      val response = httpClient.post(url(createOrderPath)) {
        contentType(ContentType.Application.Json)
        authHeader(this)
        setBody(payload)
      }
      val body = response.bodyAsText()
      when (response.status) {
        HttpStatusCode.OK, HttpStatusCode.Conflict ->
          CreateOrderResult.HttpResponseSummary(response.status.value, body)
        else -> {
          val (msg, parsed) = monolithError(response.status.value, body)
          CreateOrderResult.Error(response.status.value, msg, parsed)
        }
      }
    }
  }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult =
    guardNetwork(onNetworkError = { StoreApiKeyResult.Error(0, it, null) }) {
      val response = httpClient.put(url(MonolithPaths.STORES_API_KEY)) {
        contentType(ContentType.Application.Json)
        authHeader(this)
        setBody(MonolithJson.encodeToString(UpdateStoreApiKeyRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = MonolithJson.decodeFromString(UpdateStoreApiKeyResponse.serializer(), body)
        StoreApiKeyResult.Ok(storeId = parsed.storeId)
      } else {
        val (msg, parsed) = monolithError(response.status.value, body)
        StoreApiKeyResult.Error(response.status.value, msg, parsed)
      }
    }

  override suspend fun getStore(shopifySubdomain: String): GetStoreResult =
    guardNetwork(onNetworkError = { GetStoreResult.Error(0, it, null) }) {
      val response = httpClient.get(url(MonolithPaths.STORES)) {
        authHeader(this)
        parameter("shopify_subdomain", shopifySubdomain)
      }
      val body = response.bodyAsText()
      when (response.status) {
        HttpStatusCode.OK -> {
          val parsed = MonolithJson.decodeFromString(StoreResponse.serializer(), body)
          GetStoreResult.Ok(
            storeId = parsed.storeId,
            shopifyShopId = parsed.shopifyShopId,
            apiKey = parsed.apiKey,
          )
        }
        HttpStatusCode.NotFound -> GetStoreResult.NotFound(shopifySubdomain)
        else -> {
          val (msg, parsed) = monolithError(response.status.value, body)
          GetStoreResult.Error(response.status.value, msg, parsed)
        }
      }
    }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): UpsertVariantsResult =
    guardNetwork(onNetworkError = { UpsertVariantsResult.Error(0, it, null) }) {
      val response = httpClient.post(url(MonolithPaths.PRODUCT_VARIANTS)) {
        contentType(ContentType.Application.Json)
        authHeader(this)
        setBody(MonolithJson.encodeToString(UpsertProductVariantsRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = MonolithJson.decodeFromString(UpsertProductVariantsResponse.serializer(), body)
        UpsertVariantsResult.Ok(upserted = parsed.upserted)
      } else {
        val (msg, parsed) = monolithError(response.status.value, body)
        UpsertVariantsResult.Error(response.status.value, msg, parsed)
      }
    }

  override suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult =
    guardNetwork(onNetworkError = { GetVariantIdsResult.Error(0, it, null) }) {
      val response = httpClient.get(url(MonolithPaths.PRODUCT_VARIANTS)) {
        authHeader(this)
        parameter("shopify_subdomain", shopifySubdomain)
      }
      val body = response.bodyAsText()
      when (response.status) {
        HttpStatusCode.OK -> {
          val parsed = MonolithJson.decodeFromString(VariantIdsResponse.serializer(), body)
          GetVariantIdsResult.Ok(parsed.productVariantIds)
        }
        HttpStatusCode.NotFound -> GetVariantIdsResult.NotFound(shopifySubdomain)
        else -> {
          val (msg, parsed) = monolithError(response.status.value, body)
          GetVariantIdsResult.Error(response.status.value, msg, parsed)
        }
      }
    }

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): DeleteVariantsResult =
    guardNetwork(onNetworkError = { DeleteVariantsResult.Error(0, it, null) }) {
      val response = httpClient.delete(url(MonolithPaths.PRODUCT_VARIANTS)) {
        contentType(ContentType.Application.Json)
        authHeader(this)
        setBody(MonolithJson.encodeToString(DeleteProductVariantsRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = MonolithJson.decodeFromString(DeleteProductVariantsResponse.serializer(), body)
        DeleteVariantsResult.Ok(deleted = parsed.deleted)
      } else {
        val (msg, parsed) = monolithError(response.status.value, body)
        DeleteVariantsResult.Error(response.status.value, msg, parsed)
      }
    }

  private sealed interface CreateOrderPayload {
    data class Ok(val json: String) : CreateOrderPayload
    data class Invalid(val reason: String) : CreateOrderPayload
  }

  private fun encodeCreateOrderPayload(request: CreateShopifyOrderRequest): CreateOrderPayload {
    val json = MonolithJson.encodeToString(CreateShopifyOrderRequest.serializer(), request)
    val root = MonolithJson.parseToJsonElement(json)
    if (root !is JsonObject) {
      return CreateOrderPayload.Invalid("POST ${MonolithPaths.ORDERS} body must be a JSON object")
    }
    if (root.keys != createOrderJsonTopLevelKeys) {
      return CreateOrderPayload.Invalid(
        "POST ${MonolithPaths.ORDERS} body must only include monolith order fields; got keys=${root.keys.sorted()}",
      )
    }
    return CreateOrderPayload.Ok(json)
  }
}

/** Wraps a suspending block so a single [IOException] (network failure) becomes a typed [R] outcome via [onNetworkError]. */
private suspend inline fun <R> guardNetwork(
  onNetworkError: (message: String) -> R,
  block: () -> R,
): R = try {
  block()
} catch (e: IOException) {
  onNetworkError(e.message ?: "network error")
}
