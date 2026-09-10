package dropnext.dss.lib.shopify

import dropnext.dss.domain.ShopifyOrderId


/** Numeric REST-style id from a Shopify Admin Graphql GID (`gid://shopify/Resource/123`). */
fun legacyIdFromGid(gid: String): Long? =
  gid.substringAfterLast('/').takeIf { it.isNotEmpty() }?.toLongOrNull()

fun orderGid(orderId: ShopifyOrderId): String = "gid://shopify/Order/${orderId.value}"
