package dropnext.dss.lib.monolith

import dev.forkhandles.result4k.Result
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpsertProductVariantsRequest
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId


/** Outbound calls to the main backend (monolith). Path constants in [OutBoundMonolithPaths]. */
interface MonolithService {
  /**
   * `POST` to [OutBoundMonolithPaths.orders] — only from Shopify order webhooks. Body is exactly
   * [CreateShopifyOrderRequest] (those top-level JSON keys — no extras). A `409` means the monolith
   * already has the order and is a success ([CreateOrderOutcome.AlreadyExisted]).
   */
  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): MonolithResult<CreateOrderOutcome>

  /** `PUT` to [OutBoundMonolithPaths.storesApiKey] — persist the Shopify access token after OAuth installation. */
  suspend fun putStoreApiKey(request: UpdateStoreApiKeyRequest): MonolithResult<StoreId>

  /** `GET` to [OutBoundMonolithPaths.stores] with `?shopify_subdomain=…`; a successful `null` means the monolith knows no such store. */
  suspend fun getStore(shopifySubdomain: String): MonolithResult<MonolithStore?>

  /** `POST` to [OutBoundMonolithPaths.productVariants] — upsert product variants from a Shopify products/create or products/update webhook. Answers the upserted count. */
  suspend fun upsertProductVariants(request: UpsertProductVariantsRequest): MonolithResult<Int>

  /** `DELETE` to [OutBoundMonolithPaths.productVariants] — soft-delete variants that no longer exist in Shopify. Answers the deleted count. */
  suspend fun deleteProductVariants(request: DeleteProductVariantsRequest): MonolithResult<Int>
}

/** What every [MonolithService] call returns. */
typealias MonolithResult<T> = Result<T, MonolithError>

/**
 * Why a monolith call produced no answer, in the one distinction a caller can act on: did the
 * monolith answer at all? A [Transport] failure is worth retrying; a [Rejected] request gets the
 * same answer next time.
 */
sealed interface MonolithError {
  val message: String

  /** No answer: connection refused, timeout, … (after the client's own retries). */
  data class Transport(override val message: String) : MonolithError

  /** The monolith answered with a non-success status; [body] carries its parsed error envelope, including its own trace id. */
  data class Rejected(val status: Int, override val message: String, val body: MonolithErrorBody) : MonolithError
}

enum class CreateOrderOutcome { Created, AlreadyExisted }

data class MonolithStore(
  val storeId: StoreId,
  val shopifyShopId: ShopifyShopId,
  val apiKey: ShopifyAdminToken?,
)
