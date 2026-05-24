package dropnext.dss.lib.monolith

import dropnext.dss.lib.json.MonolithJson
import dropnext.dss.lib.monolith.dto.generated.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.dto.generated.DeleteProductVariantsRequest
import dropnext.dss.lib.monolith.dto.generated.DeleteProductVariantsResponse
import dropnext.dss.lib.monolith.dto.generated.StoreResponse
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyRequest
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyResponse
import dropnext.dss.lib.monolith.dto.generated.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.dto.generated.UpsertProductVariantsResponse
import dropnext.dss.lib.monolith.dto.generated.VariantIdsResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.serialization.KSerializer

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
  private val createOrderPath: String = OutBoundMonolithPaths.orders,
) : MonolithService {

  private val prefixedBase: String = run {
    val b = baseUrl.trimEnd('/')
    val p = apiPathPrefix?.trim()?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
    if (p == null) b else "$b/$p"
  }

  private fun url(path: String): String {
    val rel = path.trimStart('/')
    return "$prefixedBase/$rel"
  }

  private fun HttpRequestBuilder.applyAuth() {
    if (!apiKey.isNullOrBlank()) {
      header("Authorization", "Bearer $apiKey")
    }
  }

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult =
    guardNetwork(onNetworkError = { CreateOrderResult.Error(0, it, null) }) {
      val response = httpClient.post(url(createOrderPath)) {
        contentType(ContentType.Application.Json)
        applyAuth()
        setBody(MonolithJson.encodeToString(CreateShopifyOrderRequest.serializer(), request))
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

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult =
    guardNetwork(onNetworkError = { StoreApiKeyResult.Error(0, it, null) }) {
      httpClient.put(url(OutBoundMonolithPaths.storesApiKey)) {
        contentType(ContentType.Application.Json)
        applyAuth()
        setBody(MonolithJson.encodeToString(UpdateStoreApiKeyRequest.serializer(), request))
      }.foldOkOrError(
        UpdateStoreApiKeyResponse.serializer(),
        onOk = { StoreApiKeyResult.Ok(storeId = it.storeId) },
        onError = { status, msg, parsed -> StoreApiKeyResult.Error(status, msg, parsed) },
      )
    }

  override suspend fun getStore(shopifySubdomain: String): GetStoreResult =
    guardNetwork(onNetworkError = { GetStoreResult.Error(0, it, null) }) {
      val response = httpClient.get(url(OutBoundMonolithPaths.stores)) {
        applyAuth()
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
      httpClient.post(url(OutBoundMonolithPaths.productVariants)) {
        contentType(ContentType.Application.Json)
        applyAuth()
        setBody(MonolithJson.encodeToString(UpsertProductVariantsRequest.serializer(), request))
      }.foldOkOrError(
        UpsertProductVariantsResponse.serializer(),
        onOk = { UpsertVariantsResult.Ok(upserted = it.upserted) },
        onError = { status, msg, parsed -> UpsertVariantsResult.Error(status, msg, parsed) },
      )
    }

  override suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult =
    guardNetwork(onNetworkError = { GetVariantIdsResult.Error(0, it, null) }) {
      val response = httpClient.get(url(OutBoundMonolithPaths.productVariants)) {
        applyAuth()
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
      httpClient.delete(url(OutBoundMonolithPaths.productVariants)) {
        contentType(ContentType.Application.Json)
        applyAuth()
        setBody(MonolithJson.encodeToString(DeleteProductVariantsRequest.serializer(), request))
      }.foldOkOrError(
        DeleteProductVariantsResponse.serializer(),
        onOk = { DeleteVariantsResult.Ok(deleted = it.deleted) },
        onError = { status, msg, parsed -> DeleteVariantsResult.Error(status, msg, parsed) },
      )
    }
}

/**
 * Maps an HTTP response to a typed sealed outcome: deserialize the JSON body as [T] on `200 OK`,
 * or surface [MonolithCallError]-shaped fields on any other status (the [monolithError] helper
 * extracts the monolith's error body for richer log lines).
 */
private suspend inline fun <T : Any, R> HttpResponse.foldOkOrError(
  serializer: KSerializer<T>,
  onOk: (T) -> R,
  onError: (status: Int, message: String, parsed: MonolithErrorBody?) -> R,
): R {
  val body = bodyAsText()
  if (status == HttpStatusCode.OK) {
    return onOk(MonolithJson.decodeFromString(serializer, body))
  }
  val (msg, parsed) = monolithError(status.value, body)
  return onError(status.value, msg, parsed)
}

/** Wraps a suspending block so a single [IOException] (network failure) becomes a typed [R] outcome via [onNetworkError]. */
private inline fun <R> guardNetwork(
  onNetworkError: (message: String) -> R,
  block: () -> R,
): R = try {
  block()
} catch (e: IOException) {
  onNetworkError(e.message ?: "network error")
}
