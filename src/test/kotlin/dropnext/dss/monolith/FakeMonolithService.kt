package dropnext.dss.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.GetStoreResult
import dropnext.dss.lib.monolith.GetVariantIdsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.UpsertVariantsResult

/**
 * In-memory fake for [MonolithService]. Use this in tests that exercise business logic
 * (e.g. webhook handler mapping) without HTTP round-trips.
 * For HTTP-wire tests use [FakeMonolithHttpEndpoint] instead.
 */
class FakeMonolithService : MonolithService {
  // --- POST /orders ---
  var orderFailureMode = false
  var lastOrderRequest: CreateShopifyOrderRequest? = null
  var orderCallCount = 0

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult {
    orderCallCount++
    lastOrderRequest = request
    return if (orderFailureMode) {
      CreateOrderResult.Error(500, "Simulated monolith failure")
    } else {
      CreateOrderResult.HttpResponseSummary(200, """{"shopify_order_id":${request.shopifyOrderId}}""")
    }
  }

  // --- PUT /stores/api-key ---
  var storeApiKeyFailureMode = false
  var lastStoreApiKeyRequest: UpdateStoreApiKeyRequest? = null
  var storeApiKeyCallCount = 0
  var nextStoreId: Long = 1L

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult {
    storeApiKeyCallCount++
    lastStoreApiKeyRequest = request
    return if (storeApiKeyFailureMode) {
      StoreApiKeyResult.Error(500, "Simulated failure")
    } else {
      StoreApiKeyResult.Ok(storeId = nextStoreId)
    }
  }

  // --- GET /stores ---
  var getStoreFailureMode = false
  var getStoreNotFoundMode = false
  var lastGetStoreSubdomain: String? = null
  var getStoreCallCount = 0
  var nextStoreResponse: Triple<Long, Long, String?> = Triple(1L, 0L, null)

  override suspend fun getStore(shopifySubdomain: String): GetStoreResult {
    getStoreCallCount++
    lastGetStoreSubdomain = shopifySubdomain
    return when {
      getStoreFailureMode -> GetStoreResult.Error(500, "Simulated failure")
      getStoreNotFoundMode -> GetStoreResult.NotFound(shopifySubdomain)
      else -> GetStoreResult.Ok(
        storeId = nextStoreResponse.first,
        shopifyShopId = nextStoreResponse.second,
        apiKey = nextStoreResponse.third,
      )
    }
  }

  // --- POST /product-variants ---
  var upsertVariantsFailureMode = false
  var lastUpsertVariantsRequest: UpsertProductVariantsRequest? = null
  var upsertVariantsCallCount = 0

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): UpsertVariantsResult {
    upsertVariantsCallCount++
    lastUpsertVariantsRequest = request
    return if (upsertVariantsFailureMode) {
      UpsertVariantsResult.Error(500, "Simulated failure")
    } else {
      UpsertVariantsResult.Ok(upserted = request.productVariants.size)
    }
  }

  // --- GET /product-variants ---
  var getVariantIdsFailureMode = false
  var getVariantIdsNotFoundMode = false
  var lastGetVariantIdsSubdomain: String? = null
  var getVariantIdsCallCount = 0
  var nextVariantIds: List<Long> = emptyList()

  override suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult {
    getVariantIdsCallCount++
    lastGetVariantIdsSubdomain = shopifySubdomain
    return when {
      getVariantIdsFailureMode -> GetVariantIdsResult.Error(500, "Simulated failure")
      getVariantIdsNotFoundMode -> GetVariantIdsResult.NotFound(shopifySubdomain)
      else -> GetVariantIdsResult.Ok(nextVariantIds)
    }
  }

  // --- DELETE /product-variants ---
  var deleteVariantsFailureMode = false
  var lastDeleteVariantsRequest: DeleteProductVariantsRequest? = null
  var deleteVariantsCallCount = 0

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): DeleteVariantsResult {
    deleteVariantsCallCount++
    lastDeleteVariantsRequest = request
    return if (deleteVariantsFailureMode) {
      DeleteVariantsResult.Error(500, "Simulated failure")
    } else {
      DeleteVariantsResult.Ok(deleted = request.productVariantIds.size)
    }
  }

  fun reset() {
    orderFailureMode = false
    lastOrderRequest = null
    orderCallCount = 0
    storeApiKeyFailureMode = false
    lastStoreApiKeyRequest = null
    storeApiKeyCallCount = 0
    nextStoreId = 1L
    getStoreFailureMode = false
    getStoreNotFoundMode = false
    lastGetStoreSubdomain = null
    getStoreCallCount = 0
    nextStoreResponse = Triple(1L, 0L, null)
    upsertVariantsFailureMode = false
    lastUpsertVariantsRequest = null
    upsertVariantsCallCount = 0
    getVariantIdsFailureMode = false
    getVariantIdsNotFoundMode = false
    lastGetVariantIdsSubdomain = null
    getVariantIdsCallCount = 0
    nextVariantIds = emptyList()
    deleteVariantsFailureMode = false
    lastDeleteVariantsRequest = null
    deleteVariantsCallCount = 0
  }
}
