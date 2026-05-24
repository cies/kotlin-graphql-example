package dropnext.dss.lib.shopify

import dropnext.dss.lib.shopify.oauth.OutBoundShopifyOAuthPaths


/**
 * A canonical `*.myshopify.com` host. Construction goes through [parse], which lowercases,
 * trims, strips scheme/path, and expands short handles, so anything downstream can treat
 * the value as already-normalized — no `lowercase()`/`normalizeShopDomain` calls scattered
 * across handlers and workflows.
 */
@JvmInline
value class ShopDomain private constructor(val host: String) {

  /** Short subdomain only (e.g. `acme` from `acme.myshopify.com`) — used in monolith payloads. */
  val subdomainShort: String get() = host.removeSuffix(".myshopify.com")

  /** Per-shop Admin Graphql endpoint, e.g. `https://acme.myshopify.com/admin/api/2026-04/graphql.json`. */
  fun adminGraphqlUrl(apiVersion: String): String =
    "https://$host${OutBoundShopifyOAuthPaths.adminApiGraphqlJson(apiVersion)}"

  override fun toString(): String = host

  companion object {
    private val shopRegex =
      Regex("^(?:https?://)?([a-zA-Z0-9][a-zA-Z0-9\\-]*)\\.myshopify\\.com/?.*$")

    /** Plain Shopify subdomain: letters, numbers, single hyphens; no leading/trailing dot segment. */
    private val shortShopHandlePattern = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$|^[a-z0-9]$")

    /**
     * Parses install/callback `shop` to a canonical [ShopDomain].
     * Accepts full host, URL, or a short handle (e.g. `my-store`). Returns `null` for invalid input.
     */
    fun parse(raw: String): ShopDomain? = normalise(raw)?.let(::ShopDomain)

    /**
     * Resolves the shop for an inbound webhook. Prefers the [shopDomainHeader] (`X-Shopify-Shop-Domain`)
     * — Shopify's canonical signal per its webhook delivery contract — and falls back to the
     * optional JSON `domain` field when the header is missing (older topic payloads, or a
     * reverse proxy that strips the header in dev).
     */
    fun fromWebhook(shopDomainHeader: String?, webhookBodyDomain: String? = null): ShopDomain? {
      val raw = shopDomainHeader?.trim()?.takeIf { it.isNotEmpty() }
        ?: webhookBodyDomain?.trim()?.takeIf { it.isNotEmpty() }
      return raw?.let { parse(it) }
    }

    private fun normalise(raw: String): String? {
      val trimmed = raw.trim().lowercase()
      if (trimmed.endsWith(".myshopify.com")) {
        return trimmed.removePrefix("https://").removePrefix("http://").substringBefore('/')
      }
      if (!trimmed.contains('.') && shortShopHandlePattern.matches(trimmed)) {
        return "$trimmed.myshopify.com"
      }
      val match = shopRegex.matchEntire(trimmed) ?: return null
      return "${match.groupValues[1]}.myshopify.com"
    }
  }
}
