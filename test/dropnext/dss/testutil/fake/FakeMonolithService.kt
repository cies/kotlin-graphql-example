package dropnext.dss.testutil.fake

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.MonolithStore
import dropnext.dss.lib.monolith.monolithError


/** In-memory [MonolithService] for business-logic tests (no HTTP, no mock frameworks). */
class FakeMonolithService : MonolithService, RecordingFake {
  val createOrderCalls: MutableList<CreateShopifyOrderRequest> = mutableListOf()
  val putStoreApiKeyCalls: MutableList<UpdateStoreApiKeyRequest> = mutableListOf()
  val getStoreCalls: MutableList<String> = mutableListOf()
  val upsertProductVariantsCalls: MutableList<UpsertProductVariantsRequest> = mutableListOf()
  val deleteProductVariantsCalls: MutableList<DeleteProductVariantsRequest> = mutableListOf()

  /** `200` created, `409` already existed, anything else is answered as a rejection with [createOrderErrorBody]. */
  var createOrderStatus: Int = 200
  var createOrderErrorBody: String? = null

  var putStoreApiKeyStatus: Int = 200
  var putStoreApiKeyStoreId: Long = 1L

  /** When true, [getStore] answers "no such store" — for testing missing-token paths. */
  var getStoreReturnsNotFound: Boolean = false

  /** When true, [getStore] fails to reach the monolith at all, which resolves differently from "no store". */
  var getStoreTransportFailure: Boolean = false
  var getStoreToken: ShopifyAdminToken? = ShopifyAdminToken("shpat_fake")

  /** `200` answers the request's own count; anything else is a rejection with that status. */
  var upsertProductVariantsStatus: Int = 200
  var deleteProductVariantsStatus: Int = 200


  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome> {
    createOrderCalls.add(request)
    return when (createOrderStatus) {
      200 -> Success(CreateOrderOutcome.Created)
      409 -> Success(CreateOrderOutcome.AlreadyExisted)
      else -> Failure(
        rejected(
          createOrderStatus,
          createOrderErrorBody ?: """{"error":{"code":"InternalError","message":"fail","trace_id":"fake123"}}""",
        ),
      )
    }
  }

  override suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): MonolithResult<StoreId> {
    putStoreApiKeyCalls.add(request)
    return when (putStoreApiKeyStatus) {
      200 -> Success(StoreId(putStoreApiKeyStoreId))
      else -> Failure(
        rejected(putStoreApiKeyStatus, """{"error":{"code":"StoreError","message":"forced fail","trace_id":"fake-trace"}}"""),
      )
    }
  }

  override suspend fun getStore(shopifySubdomain: String): MonolithResult<MonolithStore?> {
    getStoreCalls.add(shopifySubdomain)
    return when {
      getStoreTransportFailure -> Failure(MonolithError.Transport("forced transport failure"))
      getStoreReturnsNotFound -> Success(null)
      else -> Success(MonolithStore(storeId = StoreId(1L), shopifyShopId = ShopifyShopId(99L), apiKey = getStoreToken))
    }
  }

  override suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<Int> {
    upsertProductVariantsCalls.add(request)
    return when (upsertProductVariantsStatus) {
      200 -> Success(request.productVariants.size)
      else -> Failure(rejected(upsertProductVariantsStatus, """{"error":{"code":"VariantError","message":"forced fail","trace_id":"fake-upsert"}}"""))
    }
  }

  override suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): MonolithResult<Int> {
    deleteProductVariantsCalls.add(request)
    return when (deleteProductVariantsStatus) {
      200 -> Success(request.productVariantIds.size)
      else -> Failure(rejected(deleteProductVariantsStatus, """{"error":{"code":"VariantError","message":"forced fail","trace_id":"fake-delete"}}"""))
    }
  }


  private fun rejected(status: Int, rawBody: String): MonolithError.Rejected {
    val (message, parsed) = monolithError(status, rawBody)
    return MonolithError.Rejected(status, message, parsed)
  }

  override fun clear() {
    createOrderCalls.clear()
    putStoreApiKeyCalls.clear()
    getStoreCalls.clear()
    upsertProductVariantsCalls.clear()
    deleteProductVariantsCalls.clear()
    createOrderStatus = 200
    createOrderErrorBody = null
    putStoreApiKeyStatus = 200
    putStoreApiKeyStoreId = 1L
    getStoreReturnsNotFound = false
    getStoreTransportFailure = false
    getStoreToken = ShopifyAdminToken("shpat_fake")
    upsertProductVariantsStatus = 200
    deleteProductVariantsStatus = 200
  }

}
