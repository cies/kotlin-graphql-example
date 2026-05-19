package dropnext.dss.lib.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest

/** Outbound calls to the main backend (monolith). */
interface MonolithService {
  /**
   * POST /orders — only from Shopify order webhooks. Body is exactly [CreateShopifyOrderRequest]
   * (those top-level JSON keys — no extras). Expects idempotent handling (e.g. 409 duplicate order).
   */
  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult

  /** PUT /stores/api-key — persist the Shopify access token after OAuth install. */
  suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): StoreApiKeyResult

  /** GET /stores?shopify_subdomain=… — look up a store by subdomain. */
  suspend fun getStore(shopifySubdomain: String): GetStoreResult

  /** POST /product-variants — upsert product variants from a Shopify products/create or products/update webhook. */
  suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): UpsertVariantsResult

  /** GET /product-variants?shopify_subdomain=… — list all variant IDs the monolith knows about. */
  suspend fun getProductVariantIds(shopifySubdomain: String): GetVariantIdsResult

  /** DELETE /product-variants — soft-delete variants that no longer exist in Shopify. */
  suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): DeleteVariantsResult
}

sealed interface MonolithCallError {
  val status: Int
  val errorMessage: String
  val parsed: MonolithErrorBody?
}

// --- POST /orders ---

sealed interface CreateOrderResult {
  data class HttpResponseSummary(
    val status: Int,
    val body: String,
  ) : CreateOrderResult

  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : CreateOrderResult,
    MonolithCallError
}

// --- PUT /stores/api-key ---

sealed interface StoreApiKeyResult {
  data class Ok(val storeId: Long) : StoreApiKeyResult

  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : StoreApiKeyResult,
    MonolithCallError
}

// --- GET /stores ---

sealed interface GetStoreResult {
  data class Ok(val storeId: Long, val shopifyShopId: Long, val apiKey: String?) : GetStoreResult
  data class NotFound(val shopifySubdomain: String) : GetStoreResult
  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : GetStoreResult,
    MonolithCallError
}

// --- POST /product-variants ---

sealed interface UpsertVariantsResult {
  data class Ok(val upserted: Int) : UpsertVariantsResult
  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : UpsertVariantsResult,
    MonolithCallError
}

// --- GET /product-variants ---

sealed interface GetVariantIdsResult {
  data class Ok(val productVariantIds: List<Long>) : GetVariantIdsResult
  data class NotFound(val shopifySubdomain: String) : GetVariantIdsResult
  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : GetVariantIdsResult,
    MonolithCallError
}

// --- DELETE /product-variants ---

sealed interface DeleteVariantsResult {
  data class Ok(val deleted: Int) : DeleteVariantsResult
  data class Error(
    override val status: Int,
    override val errorMessage: String,
    override val parsed: MonolithErrorBody? = null,
  ) : DeleteVariantsResult,
    MonolithCallError
}
