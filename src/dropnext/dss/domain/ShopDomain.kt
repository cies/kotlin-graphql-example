package dropnext.dss.domain

import dropnext.dss.domain.ShopDomain.Companion.parse


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

  companion object {

    /**
     * Parses install/callback `shop` to a canonical [ShopDomain].
     * Accepts full host, URL, or a short handle (e.g. `my-store`).
     * Returns `null` for invalid input.
     */
    fun parse(raw: String): ShopDomain? = normalise(raw)?.let(::ShopDomain)

    private fun normalise(raw: String): String? {
      val hostOnly = raw.trim().lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
      if (hostOnly.endsWith(".myshopify.com")) return hostOnly
      if (!hostOnly.contains('.') && subdomainPartPattern.matches(hostOnly)) {
        return "$hostOnly.myshopify.com"
      }
      return null
    }
  }
}
