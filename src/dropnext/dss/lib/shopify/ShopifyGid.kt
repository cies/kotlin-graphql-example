package dropnext.dss.lib.shopify

/** Numeric REST-style id from a Shopify Admin Graphql GID (`gid://shopify/Resource/123`). */
fun legacyIdFromGid(gid: String): Long? =
  gid.substringAfterLast('/').takeIf { it.isNotEmpty() }?.toLongOrNull()

fun fulfillmentGid(legacyId: Long): String = "gid://shopify/Fulfillment/$legacyId"

fun fulfillmentOrderGid(legacyId: Long): String = "gid://shopify/FulfillmentOrder/$legacyId"

fun orderGid(legacyId: Long): String = "gid://shopify/Order/$legacyId"

fun shopifyShopIdFromShopGid(shopGid: String): Long? = legacyIdFromGid(shopGid)
