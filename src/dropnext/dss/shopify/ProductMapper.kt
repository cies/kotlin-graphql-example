package dropnext.dss.shopify

import dropnext.dss.lib.dss.dto.ProductStatus
import dropnext.dss.lib.dss.dto.ProductVariantItem
import dropnext.dss.lib.dss.dto.SelectedOption
import dropnext.graphql.generated.getproductbyid.Media
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.Product
import kotlin.math.roundToLong

/**
 * Maps a Shopify Admin Graphql product (from [GetProductById]) to a list of [ProductVariantItem]
 * DTOs ready to be sent to the monolith via `POST` to [dropnext.dss.path.MonolithPaths.PRODUCT_VARIANTS].
 *
 * @param currencyCode ISO 4217 currency code for the shop (e.g. "USD"); obtain from the
 *   `shop { currencyCode }` field returned alongside the product query.
 */
fun Product.toProductVariantItems(currencyCode: String): List<ProductVariantItem> {
  val productLegacyId = legacyResourceId.toLongOrNull() ?: return emptyList()
  val productImageUrls = catalogImageUrls()
  val mappedStatus = status.toProductStatus()
  return variants.edges.mapNotNull { variantEdge ->
    val v = variantEdge.node
    val variantLegacyId = v.legacyResourceId.toLongOrNull() ?: return@mapNotNull null
    val priceMinor = decimalToMinorUnits(v.price)
    ProductVariantItem(
      productVariantId = variantLegacyId,
      productId = productLegacyId,
      productTitle = title,
      productDescription = description,
      productDescriptionHtml = descriptionHtml,
      productVendor = vendor,
      productType = productType,
      productTags = tags,
      productHandle = handle,
      productStatus = mappedStatus,
      productImages = productImageUrls,
      productPublishedAt = publishedAt,
      productCreatedAt = createdAt,
      productUpdatedAt = updatedAt,
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

private fun dropnext.graphql.generated.getproductbyid.ProductVariant.variantImageUrlOrNull(): String? =
  media.edges.firstOrNull()?.node?.imageUrlOrNull()

private fun Media.imageUrlOrNull(): String? =
  when (this) {
    is MediaImage -> image?.url
    else -> null
  }

private fun dropnext.graphql.generated.enums.ProductStatus.toProductStatus(): ProductStatus =
  when (this) {
    dropnext.graphql.generated.enums.ProductStatus.ACTIVE -> ProductStatus.ACTIVE
    dropnext.graphql.generated.enums.ProductStatus.ARCHIVED -> ProductStatus.ARCHIVED
    dropnext.graphql.generated.enums.ProductStatus.DRAFT -> ProductStatus.DRAFT
    else -> ProductStatus.ACTIVE
  }

private fun decimalToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}
