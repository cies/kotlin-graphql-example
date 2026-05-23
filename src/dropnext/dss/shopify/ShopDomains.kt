package dropnext.dss.shopify

import dropnext.dss.path.ShopifyPaths

private val shopRegex =
  Regex("^(?:https?://)?([a-zA-Z0-9][a-zA-Z0-9\\-]*)\\.myshopify\\.com/?.*$")

/**
 * Normalizes install/callback `shop` to `name.myshopify.com`.
 * Accepts full host, URL, or a **short handle** (e.g. `my-store`) for DSS and query parameters.
 */
// TODO: Maybe make it part of a value type
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

fun adminGraphqlJsonUrl(shopDomain: String, apiVersion: String): String =
  "https://$shopDomain${ShopifyPaths.adminApiGraphqlJson(apiVersion)}"

/** Short handle (e.g. `store` from `store.myshopify.com`) for monolith / DSS payloads. */
fun shopifySubdomainShort(shopDomain: String): String = shopDomain.removeSuffix(".myshopify.com")

/**
 * Resolves `*.myshopify.com` host for webhooks: prefers [shopDomainHeader] (`X-Shopify-Shop-Domain`),
 * then optional JSON `domain` from the webhook body.
 */
// TODO(cies): why the header?
fun shopMyShopifyHostFromWebhook(shopDomainHeader: String?, webhookBodyDomain: String? = null): String? {
  val raw = shopDomainHeader?.trim()?.takeIf { it.isNotEmpty() }
    ?: webhookBodyDomain?.trim()?.takeIf { it.isNotEmpty() }
  return raw?.let { normalizeShopDomain(it) }
}
