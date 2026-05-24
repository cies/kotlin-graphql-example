package dropnext.dss.testing.fake

import dropnext.dss.lib.monolith.dto.generated.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.dto.generated.DeleteProductVariantsRequest
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyRequest
import dropnext.dss.lib.monolith.dto.generated.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.GetStoreResult
import dropnext.dss.lib.monolith.GetVariantIdsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.UpsertVariantsResult
import dropnext.dss.lib.monolith.monolithError

/**
 * In-memory [MonolithService] for business-logic tests (no HTTP, no mock frameworks).
 */
class FakeMonolithService : MonolithService {
  var lastCreateOrder: CreateShopifyOrderRequest? = null
    private set
  var createOrderCallCount: Int = 0
    private set

  var lastPutStoreApiKey: UpdateStoreApiKeyRequest? = null
    private set
  var putStoreApiKeyCallCount: Int = 0
    private set

  val upsertProductVariantsCalls: MutableList<UpsertProductVariantsRequest> = mutableListOf()
  val deleteProductVariantsCalls: MutableList<DeleteProductVariantsRequest> = mutableListOf()

  var createOrderStatus: Int = 200
  var createOrderErrorBody: String? = null

  var putStoreApiKeyStatus: Int = 200
  var putStoreApiKeyStoreId: Long = 1L

  /** When true, [getStore] returns [GetStoreResult.NotFound] — for testing missing-token paths. */
  var getStoreReturnsNotFound: Boolean = false

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult {
    createOrderCallCount++
    lastCreateOrder = request
    return when (createOrderStatus) {
      200 -> CreateOrderResult.HttpResponseSummary(200, """{"shopify_order_id":${request.shopifyOrderId}}""")
      409 -> CreateOrderResult.HttpResponseSummary(409, """{"error":"Order already exists."}""")
      else -> {
        val raw = createOrderErrorBody ?: """{"error":{"code":"InternalError","message":"fail","trace_id":"fake123"}}"""
        val (msg, parsed) = monolithError(createOrderStatus, raw)
        CreateOrderResult.Error(createOrderStatus, msg, parsed)
      }
    }
  }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult {
    putStoreApiKeyCallCount++
    lastPutStoreApiKey = request
    return when (putStoreApiKeyStatus) {
      200 -> StoreApiKeyResult.Ok(storeId = putStoreApiKeyStoreId)
      else -> {
        val raw = """{"error":{"code":"StoreError","message":"forced fail","trace_id":"fake-trace"}}"""
        val (msg, parsed) = monolithError(putStoreApiKeyStatus, raw)
        StoreApiKeyResult.Error(putStoreApiKeyStatus, msg, parsed)
      }
    }
  }

  override suspend fun getStore(shopifySubdomain: String): GetStoreResult =
    if (getStoreReturnsNotFound) GetStoreResult.NotFound(shopifySubdomain)
    else GetStoreResult.Ok(storeId = 1L, shopifyShopId = 99L, apiKey = "shpat_fake")

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): UpsertVariantsResult {
    upsertProductVariantsCalls.add(request)
    return UpsertVariantsResult.Ok(upserted = request.productVariants.size)
  }

  override suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult =
    GetVariantIdsResult.Ok(emptyList())

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): DeleteVariantsResult {
    deleteProductVariantsCalls.add(request)
    return DeleteVariantsResult.Ok(deleted = request.productVariantIds.size)
  }
}
