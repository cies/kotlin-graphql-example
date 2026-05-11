package dropnext.dss.lib.monolith

import dropnext.dss.lib.dss.dto.CreateOrderResponse
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsResponse
import dropnext.dss.lib.dss.dto.StoreResponse
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyResponse
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsResponse
import dropnext.dss.lib.dss.dto.VariantIdsResponse
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json

class HttpMonolithService(
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiKey: String?,
  private val createOrderPath: String = "/orders",
) : MonolithService {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private fun url(path: String): String = baseUrl.trimEnd('/') + path

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult =
    runCatching {
      val response = httpClient.post(url(createOrderPath)) {
        contentType(ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        setBody(json.encodeToString(CreateShopifyOrderRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        CreateOrderResult.HttpResponseSummary(response.status.value, body)
      } else {
        CreateOrderResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      CreateOrderResult.Error(0, e.message ?: "network error")
    }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult =
    runCatching {
      val response = httpClient.put(url("/stores/api-key")) {
        contentType(ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        setBody(json.encodeToString(UpdateStoreApiKeyRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = json.decodeFromString(UpdateStoreApiKeyResponse.serializer(), body)
        StoreApiKeyResult.Ok(storeId = parsed.storeId)
      } else {
        StoreApiKeyResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      StoreApiKeyResult.Error(0, e.message ?: "network error")
    }

  override suspend fun getStore(shopifySubdomain: String): GetStoreResult =
    runCatching {
      val response = httpClient.get(url("/stores")) {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        parameter("shopify_subdomain", shopifySubdomain)
      }
      val body = response.bodyAsText()
      when (response.status) {
        HttpStatusCode.OK -> {
          val parsed = json.decodeFromString(StoreResponse.serializer(), body)
          GetStoreResult.Ok(
            storeId = parsed.storeId,
            shopifyShopId = parsed.shopifyShopId,
            apiKey = parsed.apiKey,
          )
        }
        HttpStatusCode.NotFound -> GetStoreResult.NotFound(shopifySubdomain)
        else -> GetStoreResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      GetStoreResult.Error(0, e.message ?: "network error")
    }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): UpsertVariantsResult =
    runCatching {
      val response = httpClient.post(url("/product-variants")) {
        contentType(ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        setBody(json.encodeToString(UpsertProductVariantsRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = json.decodeFromString(UpsertProductVariantsResponse.serializer(), body)
        UpsertVariantsResult.Ok(upserted = parsed.upserted)
      } else {
        UpsertVariantsResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      UpsertVariantsResult.Error(0, e.message ?: "network error")
    }

  override suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult =
    runCatching {
      val response = httpClient.get(url("/product-variants")) {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        parameter("shopify_subdomain", shopifySubdomain)
      }
      val body = response.bodyAsText()
      when (response.status) {
        HttpStatusCode.OK -> {
          val parsed = json.decodeFromString(VariantIdsResponse.serializer(), body)
          GetVariantIdsResult.Ok(parsed.productVariantIds)
        }
        HttpStatusCode.NotFound -> GetVariantIdsResult.NotFound(shopifySubdomain)
        else -> GetVariantIdsResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      GetVariantIdsResult.Error(0, e.message ?: "network error")
    }

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): DeleteVariantsResult =
    runCatching {
      val response = httpClient.delete(url("/product-variants")) {
        contentType(ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        setBody(json.encodeToString(DeleteProductVariantsRequest.serializer(), request))
      }
      val body = response.bodyAsText()
      if (response.status == HttpStatusCode.OK) {
        val parsed = json.decodeFromString(DeleteProductVariantsResponse.serializer(), body)
        DeleteVariantsResult.Ok(deleted = parsed.deleted)
      } else {
        DeleteVariantsResult.Error(response.status.value, body.take(512))
      }
    }.getOrElse { e ->
      DeleteVariantsResult.Error(0, e.message ?: "network error")
    }
}
