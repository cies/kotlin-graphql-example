package com.example.lib.dss

import com.example.config.ShopifyConfig

data class DssAppConfig(
  val shopify: ShopifyConfig,
  /** Base URL for monolith (e.g. `https://monolith.internal`). If null, order webhook does not POST. */
  val monolithBaseUrl: String?,
  /** Sent as `Authorization: Bearer …` when non-blank. */
  val monolithApiKey: String?,
  /** Path appended to monolith base for create order (default `/orders`). */
  val monolithCreateOrderPath: String,
  /** When set, DSS REST routes require header `X-DSS-Internal-Secret` (except health/install/oauth/webhooks). */
  val dssInternalSecret: String?,
  /**
   * Normalized `shop.myshopify.com` → Admin API access token. No server-side database; callers pass
   * `X-Shopify-Access-Token` or configure this map via env (see [shopAccessTokensFromEnv]).
   */
  val shopAccessTokens: Map<String, String>,
  val enableDemoRoutes: Boolean,
  /** When true, serve the HTML test harness at `GET /dev/test-harness` (see `ENABLE_TEST_HARNESS`). */
  val enableTestHarness: Boolean,
  /**
   * When true (only with [enableTestHarness]), DSS + demo skip real Shopify calls and return stub 200/OK
   * so the HTML harness can be exercised without a real token. See `DSS_SANDBOX_FAKE_SHOPIFY`.
   */
  val sandboxFakeShopify: Boolean,
  /** When false (default), `MONOLITH_BASE_URL` must be `https://` (except unset). */
  val allowInsecureMonolithUrl: Boolean,
) {
  companion object {
    fun fromEnv(): DssAppConfig? {
      val shopify = ShopifyConfig.fromEnv() ?: return null
      val base = System.getenv("MONOLITH_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
      val key = System.getenv("MONOLITH_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
      val rawPath =
        System.getenv("MONOLITH_CREATE_ORDER_PATH")?.trim()?.takeIf { it.isNotEmpty() } ?: "/orders"
      val path =
        when {
          rawPath.startsWith('/') -> rawPath
          else -> "/$rawPath"
        }
      val secret = System.getenv("DSS_INTERNAL_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
      val testHarness =
        System.getenv("ENABLE_TEST_HARNESS")?.trim()?.equals("true", ignoreCase = true) == true
      val demosExplicit =
        System.getenv("ENABLE_DEMO_ROUTES")?.trim()?.equals("true", ignoreCase = true) == true
      val demos = demosExplicit || testHarness
      val allowInsecure =
        System.getenv("DSS_ALLOW_INSECURE_MONOLITH")?.trim()?.equals("true", ignoreCase = true) == true
      val fakeShopify =
        testHarness &&
          System.getenv("DSS_SANDBOX_FAKE_SHOPIFY")?.trim()?.equals("true", ignoreCase = true) == true
      if (base != null && base.startsWith("http:", ignoreCase = true) && !allowInsecure) {
        error(
          "MONOLITH_BASE_URL must use https. For local http only, set DSS_ALLOW_INSECURE_MONOLITH=true",
        )
      }
      return DssAppConfig(
        shopify = shopify,
        monolithBaseUrl = base,
        monolithApiKey = key,
        monolithCreateOrderPath = path,
        dssInternalSecret = secret,
        shopAccessTokens = shopAccessTokensFromEnv(enableTestHarness = testHarness),
        enableDemoRoutes = demos,
        enableTestHarness = testHarness,
        sandboxFakeShopify = fakeShopify,
        allowInsecureMonolithUrl = allowInsecure,
      )
    }
  }
}
