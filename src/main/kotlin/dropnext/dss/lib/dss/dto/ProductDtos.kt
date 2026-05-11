package dropnext.dss.lib.dss.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductStatus {
  @SerialName("active") ACTIVE,
  @SerialName("draft") DRAFT,
  @SerialName("archived") ARCHIVED,
}

@Serializable
data class SelectedOption(
  @SerialName("name") val name: String,
  @SerialName("value") val value: String,
)

@Serializable
data class ProductVariantItem(
  @SerialName("product_variant_id") val productVariantId: Long,
  @SerialName("product_id") val productId: Long,
  @SerialName("product_title") val productTitle: String,
  @SerialName("product_description") val productDescription: String,
  @SerialName("product_description_html") val productDescriptionHtml: String,
  @SerialName("product_vendor") val productVendor: String,
  @SerialName("product_type") val productType: String,
  @SerialName("product_tags") val productTags: List<String>,
  @SerialName("product_handle") val productHandle: String,
  @SerialName("product_status") val productStatus: ProductStatus,
  @SerialName("product_images") val productImages: List<String>,
  @SerialName("product_published_at") val productPublishedAt: String? = null,
  @SerialName("product_created_at") val productCreatedAt: String,
  @SerialName("product_updated_at") val productUpdatedAt: String,
  @SerialName("title") val title: String,
  @SerialName("sku") val sku: String? = null,
  @SerialName("barcode") val barcode: String? = null,
  @SerialName("price_in_minor_units") val priceInMinorUnits: Long,
  @SerialName("price_currency") val priceCurrency: String,
  @SerialName("selected_options") val selectedOptions: List<SelectedOption>,
  @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class UpsertProductVariantsRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("product_variants") val productVariants: List<ProductVariantItem>,
)

@Serializable
data class UpsertProductVariantsResponse(
  @SerialName("upserted") val upserted: Int,
)

@Serializable
data class DeleteProductVariantsRequest(
  @SerialName("shopify_subdomain") val shopifySubdomain: String,
  @SerialName("product_variant_ids") val productVariantIds: List<Long>,
)

@Serializable
data class DeleteProductVariantsResponse(
  @SerialName("deleted") val deleted: Int,
)

@Serializable
data class VariantIdsResponse(
  @SerialName("product_variant_ids") val productVariantIds: List<Long>,
)
