package dropnext.dss.shopify

import dropnext.dss.lib.monolith.dto.generated.ProductStatus
import dropnext.dss.lib.monolith.dto.generated.ProductVariantItem
import dropnext.dss.lib.monolith.dto.generated.SelectedOption
import dropnext.dss.shopify.shopifyMoneyAmountForWire
import dropnext.graphql.generated.getproductbyid.Media
import dropnext.graphql.generated.getproductbyid.MediaImage
import dropnext.graphql.generated.getproductbyid.Product
import dropnext.graphql.generated.getproductbyid.ProductVariant

/**
 * Maps a Shopify Admin Graphql product (from [GetProductById]) to a list of [ProductVariantItem]
 * DTOs ready to be sent to the monolith via `POST` to [dropnext.dss.lib.monolith.OutBoundMonolithPaths.productVariants].
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
      priceAsString = shopifyMoneyAmountForWire(v.price),
      priceCurrency = currencyCode,
      selectedOptions = v.selectedOptions.map { opt -> SelectedOption(opt.name, opt.value) },
      imageUrl = v.variantImageUrlOrNull(),
    )
  }
}

private fun Product.catalogImageUrls(): List<String> =
  media.edges.mapNotNull { edge -> edge.node.imageUrlOrNull() }

private fun ProductVariant.variantImageUrlOrNull(): String? =
  media.edges.firstOrNull()?.node?.imageUrlOrNull()

private fun Media.imageUrlOrNull(): String? = when (this) {
  is MediaImage -> image?.url
  else -> null
}

private fun dropnext.graphql.generated.enums.ProductStatus.toProductStatus(): ProductStatus =
  when (this) {
    dropnext.graphql.generated.enums.ProductStatus.ACTIVE -> ProductStatus.ACTIVE
    dropnext.graphql.generated.enums.ProductStatus.ARCHIVED -> ProductStatus.ARCHIVED
    dropnext.graphql.generated.enums.ProductStatus.DRAFT -> ProductStatus.DRAFT

    // Better than an else branch...
    dropnext.graphql.generated.enums.ProductStatus.UNLISTED,
    dropnext.graphql.generated.enums.ProductStatus.__UNKNOWN_VALUE -> ProductStatus.ACTIVE
  }

