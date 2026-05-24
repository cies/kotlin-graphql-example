package dropnext.dss.lib.shopify

/** Numeric REST-style id from a Shopify Admin Graphql GID (`gid://shopify/Resource/123`). */
fun legacyIdFromGid(gid: String): Long? =
  gid.substringAfterLast('/').takeIf { it.isNotEmpty() }?.toLongOrNull()

fun orderGid(legacyId: Long): String = "gid://shopify/Order/$legacyId"

/**
 * Resolves a user-supplied id parameter to an order GID. Accepts either a full
 * `gid://shopify/Order/<n>` value or a bare numeric id. Returns `null` for anything else.
 */
fun orderGidFromParam(idParam: String): String? {
  if (idParam.startsWith("gid://")) return idParam
  val numeric = idParam.toLongOrNull() ?: return null
  return orderGid(numeric)
}
