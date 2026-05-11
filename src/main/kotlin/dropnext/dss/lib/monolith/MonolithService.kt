package dropnext.dss.lib.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest

/** Outbound calls to the main backend (monolith). */
interface MonolithService {
  /** POST /orders — called on Shopify orders/create webhook. */
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

// --- POST /orders ---

sealed interface CreateOrderResult {
  data class HttpResponseSummary(
    val status: Int,
    val body: String,
  ) : CreateOrderResult

  data class Error(
    val status: Int,
    val errorMessage: String,
  ) : CreateOrderResult
}

// --- PUT /stores/api-key ---

sealed interface StoreApiKeyResult {
  data class Ok(val storeId: Long) : StoreApiKeyResult
  data class Error(val status: Int, val errorMessage: String) : StoreApiKeyResult
}

// --- GET /stores ---

sealed interface GetStoreResult {
  data class Ok(val storeId: Long, val shopifyShopId: Long, val apiKey: String?) : GetStoreResult
  data class NotFound(val shopifySubdomain: String) : GetStoreResult
  data class Error(val status: Int, val errorMessage: String) : GetStoreResult
}

// --- POST /product-variants ---

sealed interface UpsertVariantsResult {
  data class Ok(val upserted: Int) : UpsertVariantsResult
  data class Error(val status: Int, val errorMessage: String) : UpsertVariantsResult
}

// --- GET /product-variants ---

sealed interface GetVariantIdsResult {
  data class Ok(val productVariantIds: List<Long>) : GetVariantIdsResult
  data class NotFound(val shopifySubdomain: String) : GetVariantIdsResult
  data class Error(val status: Int, val errorMessage: String) : GetVariantIdsResult
}

// --- DELETE /product-variants ---

sealed interface DeleteVariantsResult {
  data class Ok(val deleted: Int) : DeleteVariantsResult
  data class Error(val status: Int, val errorMessage: String) : DeleteVariantsResult
}
