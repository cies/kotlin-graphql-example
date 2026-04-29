package com.example.lib.dss

import com.example.shopify.normalizeShopDomain

private const val DEFAULT_HARNESS_FAKE_TOKEN = "shpat_sandbox_harness_not_for_production"

/**
 * `DSS_SHOP_ACCESS_TOKENS`: comma-separated `shop|token` pairs.
 * Shop may be short handle or full `*.myshopify.com` host; keys are normalized to full host.
 *
 * When [enableTestHarness] is true, [SANDBOX_ACCESS_TOKEN] / [SANDBOX_SHOP] are merged; if the token is still
 * absent, a non-production placeholder is injected so the HTML harness can run without OAuth.
 */
fun shopAccessTokensFromEnv(
  enableTestHarness: Boolean,
): Map<String, String> {
  val raw = System.getenv("DSS_SHOP_ACCESS_TOKENS")?.trim().orEmpty()
  val parsed =
    raw.split(',').mapNotNull { segment ->
      val part = segment.trim()
      if (part.isEmpty()) return@mapNotNull null
      val idx = part.indexOf('|')
      if (idx <= 0 || idx == part.length - 1) return@mapNotNull null
      val shopRaw = part.substring(0, idx).trim()
      val token = part.substring(idx + 1).trim()
      val host = normalizeShopDomain(shopRaw) ?: return@mapNotNull null
      host.lowercase() to token
    }.toMap()
  val merged = parsed.toMutableMap()
  val sandboxShopRaw =
    System.getenv("SANDBOX_SHOP")?.trim()?.takeIf { it.isNotEmpty() } ?: "harness-sandbox"
  val sandboxHost =
    normalizeShopDomain(sandboxShopRaw) ?: error("Invalid SANDBOX_SHOP: $sandboxShopRaw")
  val sandboxTok =
    System.getenv("SANDBOX_ACCESS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
      ?: if (enableTestHarness) DEFAULT_HARNESS_FAKE_TOKEN else null
  if (sandboxTok != null) {
    merged[sandboxHost.lowercase()] = sandboxTok
  }
  return merged
}
