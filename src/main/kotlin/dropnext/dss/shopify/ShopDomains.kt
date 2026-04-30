package dropnext.dss.shopify

private val shopRegex =
  Regex("^(?:https?://)?([a-zA-Z0-9][a-zA-Z0-9\\-]*)\\.myshopify\\.com/?.*$")

/**
 * Normalizes install/callback `shop` to `name.myshopify.com`.
 * Accepts full host, URL, or a **short handle** (e.g. `my-store`) for DSS and query parameters.
 */
fun normalizeShopDomain(raw: String): String? {
  val trimmed = raw.trim().lowercase()
  if (trimmed.endsWith(".myshopify.com")) {
    val host = trimmed.removePrefix("https://").removePrefix("http://").substringBefore('/')
    return host
  }
  if (!trimmed.contains('.')) {
    if (
      shortShopHandlePattern.matches(trimmed)
    ) {
      return "$trimmed.myshopify.com"
    }
  }
  val match = shopRegex.matchEntire(trimmed) ?: return null
  return "${match.groupValues[1]}.myshopify.com"
}

/** Plain Shopify subdomain: letters, numbers, single hyphens; no leading/trailing dot segment. */
private val shortShopHandlePattern = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$|^[a-z0-9]$")

fun adminGraphqlJsonUrl(shop: String, apiVersion: String): String =
  "https://$shop/admin/api/$apiVersion/graphql.json"

/** Short handle (e.g. `store` from `store.myshopify.com`) for monolith / DSS payloads. */
fun shopifySubdomainShort(shop: String): String = shop.removeSuffix(".myshopify.com")
