package dropnext.dss.config

import dropnext.dss.lib.shopify.ShopDomain


/** `DSS_SHOP_ACCESS_TOKENS`: comma-separated `shop|token` pairs normalized through [ShopDomain.parse]. */
fun shopAccessTokensFromEnv(): Map<ShopDomain, String> =
  System.getenv("DSS_SHOP_ACCESS_TOKENS")?.trim().orEmpty().split(',')
    .mapNotNull { parseSegment(it) }.toMap()

private fun parseSegment(segment: String): Pair<ShopDomain, String>? {
  val part = segment.trim()
  if (part.isEmpty()) return null
  val idx = part.indexOf('|')
  if (idx <= 0 || idx == part.length - 1) return null
  val shopRaw = part.substring(0, idx).trim()
  val token = part.substring(idx + 1).trim()
  val host = ShopDomain.parse(shopRaw) ?: return null
  return host to token
}
