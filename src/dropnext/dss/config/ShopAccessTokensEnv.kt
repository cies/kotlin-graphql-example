package dropnext.dss.config

import dropnext.dss.lib.shopify.ShopDomain


private const val DEFAULT_HARNESS_FAKE_TOKEN = "shpat_sandbox_harness_not_for_production"

/**
 * `DSS_SHOP_ACCESS_TOKENS`: comma-separated `shop|token` pairs.
 * Shop may be short handle or full `*.myshopify.com` host; keys are normalized via [ShopDomain.parse].
 *
 * When [enableTestHarness] is true, `SANDBOX_ACCESS_TOKEN` / `SANDBOX_SHOP` are merged;
 * if the token is still absent, a non-production placeholder is injected so the HTML harness can run without OAuth.
 */
fun shopAccessTokensFromEnv(
  enableTestHarness: Boolean,
): Map<ShopDomain, String> {
  val parsed = System.getenv("DSS_SHOP_ACCESS_TOKENS")?.trim().orEmpty().split(',')
    .mapNotNull { parseSegment(it) }.toMap()

  val sandboxShopRaw = System.getenv("SANDBOX_SHOP")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "harness-sandbox"
  val sandboxHost = ShopDomain.parse(sandboxShopRaw)
    ?: error("Invalid SANDBOX_SHOP: $sandboxShopRaw")
  val sandboxTok = System.getenv("SANDBOX_ACCESS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    ?: if (enableTestHarness) DEFAULT_HARNESS_FAKE_TOKEN else null

  if (sandboxTok == null) {
    return parsed
  }
  return parsed + mapOf(sandboxHost to sandboxTok)
}

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
