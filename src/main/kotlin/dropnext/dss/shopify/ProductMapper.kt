package dropnext.dss.shopify

import dropnext.dss.lib.dss.dto.ProductStatus
import dropnext.dss.lib.dss.dto.ProductVariantItem
import dropnext.dss.lib.dss.dto.SelectedOption
import com.example.graphql.generated.getproductbyid.Media
import com.example.graphql.generated.getproductbyid.MediaImage
import com.example.graphql.generated.getproductbyid.Product
import kotlin.math.roundToLong

/**
 * Maps a Shopify Admin GraphQL product (from [GetProductById]) to a list of [ProductVariantItem]
 * DTOs ready to be sent to the monolith via POST /product-variants.
 *
 * @param currencyCode ISO 4217 currency code for the shop (e.g. "USD"); obtain from the
 *   `shop { currencyCode }` field returned alongside the product query.
 */
fun Product.toProductVariantItems(currencyCode: String): List<ProductVariantItem> {
  val productLegacyId = legacyResourceId.toString().toLongOrNull() ?: return emptyList()
  val productImageUrls = catalogImageUrls()
  val mappedStatus = status.toProductStatus()
  return variants.edges.mapNotNull { variantEdge ->
    val v = variantEdge.node
    val variantLegacyId = v.legacyResourceId.toString().toLongOrNull() ?: return@mapNotNull null
    val priceMinor = decimalToMinorUnits(v.price.toString())
    ProductVariantItem(
      productVariantId = variantLegacyId,
      productId = productLegacyId,
      productTitle = title,
      productDescription = description ?: "",
      productDescriptionHtml = descriptionHtml ?: "",
      productVendor = vendor,
      productType = productType,
      productTags = tags,
      productHandle = handle,
      productStatus = mappedStatus,
      productImages = productImageUrls,
      productPublishedAt = publishedAt?.toString(),
      productCreatedAt = createdAt.toString(),
      productUpdatedAt = updatedAt.toString(),
      title = v.title,
      sku = v.sku,
      barcode = v.barcode,
      priceInMinorUnits = priceMinor,
      priceCurrency = currencyCode,
      selectedOptions = v.selectedOptions.map { opt -> SelectedOption(name = opt.name, value = opt.value) },
      imageUrl = v.variantImageUrlOrNull(),
    )
  }
}

private fun Product.catalogImageUrls(): List<String> =
  media.edges.mapNotNull { edge -> edge.node.imageUrlOrNull() }

private fun com.example.graphql.generated.getproductbyid.ProductVariant.variantImageUrlOrNull(): String? =
  media.edges.firstOrNull()?.node?.imageUrlOrNull()

private fun Media.imageUrlOrNull(): String? =
  when (this) {
    is MediaImage -> image?.url?.toString()
    else -> null
  }

private fun com.example.graphql.generated.enums.ProductStatus.toProductStatus(): ProductStatus =
  when (this) {
    com.example.graphql.generated.enums.ProductStatus.ACTIVE -> ProductStatus.ACTIVE
    com.example.graphql.generated.enums.ProductStatus.ARCHIVED -> ProductStatus.ARCHIVED
    com.example.graphql.generated.enums.ProductStatus.DRAFT -> ProductStatus.DRAFT
    else -> ProductStatus.ACTIVE
  }

private fun decimalToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}
