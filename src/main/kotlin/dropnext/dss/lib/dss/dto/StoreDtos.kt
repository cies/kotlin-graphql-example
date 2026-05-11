package dropnext.dss.lib.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StoreResponse(
  @SerialName("store_id") val storeId: Long,
  @SerialName("shopify_shop_id") val shopifyShopId: Long,
  @SerialName("api_key") val apiKey: String?,
)

@Serializable
data class UpdateStoreApiKeyRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("shopify_shop_id") val shopifyShopId: Long,
  @SerialName("api_key") val apiKey: String,
)

@Serializable
data class UpdateStoreApiKeyResponse(
  @SerialName("store_id") val storeId: Long,
)

/** Inbound: caller → DSS — set the Shopify Admin API token for a store without going through OAuth. */
@Serializable
data class PutShopAccessTokenRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("api_key") val apiKey: String,
  /** Optional: when provided, the token is also forwarded to the monolith via PUT /stores/api-key. */
  @SerialName("shopify_shop_id") val shopifyShopId: Long? = null,
)

@Serializable
data class PutShopAccessTokenResponse(
  @SerialName("shop") val shop: String,
)
