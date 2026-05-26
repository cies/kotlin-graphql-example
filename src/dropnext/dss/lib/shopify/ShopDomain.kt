package dropnext.dss.lib.shopify

import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.shopify.ShopDomain.Companion.parse
import dropnext.dss.lib.shopify.oauth.OutBoundShopifyOAuthPaths
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/** Subdomain part: letters, numbers, single hyphens; no leading/trailing dot/hyphen. */
private val subdomainPartPattern = Regex("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?$")

/**
 * A canonical `*.myshopify.com` hostname.
 *
 * Construction goes through [parse], which lowercases, trims, strips scheme/path, and
 * expands short handles, so anything downstream can treat the value, obtained with [toString] or
 * [subdomainOnly], as already-normalized.
 */
@JvmInline
value class ShopDomain private constructor(val normalizedShopifyHost: String) {

  override fun toString(): String = normalizedShopifyHost

  /** Short subdomain only (e.g. `acme` from `acme.myshopify.com`) — used in monolith payloads. */
  val subdomainOnly: String get() = normalizedShopifyHost.removeSuffix(".myshopify.com")

  /** Per-shop Admin Graphql endpoint, e.g. `https://acme.myshopify.com/admin/api/2026-04/graphql.json`. */
  fun adminGraphqlUrl(apiVersion: String): String =
    "https://$normalizedShopifyHost${OutBoundShopifyOAuthPaths.adminApiGraphqlJson(apiVersion)}"

  companion object {

    /**
     * Parses install/callback `shop` to a canonical [ShopDomain].
     * Accepts full host, URL, or a short handle (e.g. `my-store`).
     * Returns `null` for invalid input.
     */
    fun parse(raw: String): ShopDomain? = normalise(raw)?.let(::ShopDomain)

    /**
     * Resolves the shop for an inbound webhook.
     * Shopify notoriously puts the domain either in the header or in the JSON body.
     *
     * This function prefers the [shopDomainHeader] (`X-Shopify-Shop-Domain`)
     * — Shopify's canonical signal per its webhook delivery contract — and falls back to the
     * optional JSON `domain` field when the header is missing (older topic payloads, or a
     * reverse proxy that strips the header in dev).
     */
    fun fromWebhook(shopDomainHeader: String?, webhookBody: String?): ShopDomain? {
      shopDomainHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { return parse(it) }

      if (webhookBody == null) return null
      val root = runCatching { AppJson.parseToJsonElement(webhookBody).jsonObject }.getOrNull()
        ?: return null
      return root["domain"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { parse(it) }
    }

    private fun normalise(raw: String): String? {
      val lowercaseTrimmed = raw.trim().lowercase()
      if (lowercaseTrimmed.endsWith(".myshopify.com") || lowercaseTrimmed.endsWith(".myshopify.com/")) {
        return lowercaseTrimmed
          .removePrefix("https://")
          .removePrefix("http://")
          .substringBefore('/')
      }
      if (!lowercaseTrimmed.contains('.') && subdomainPartPattern.matches(lowercaseTrimmed)) {
        return "$lowercaseTrimmed.myshopify.com"
      }
      return null
    }
  }
}
