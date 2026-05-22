package dropnext.dss.config

import java.util.concurrent.ConcurrentHashMap


data class DssAppConfig(

  val shopify: ShopifyConfig,

  /** HTTPS base URL for monolith outbound calls; DSS appends paths from [MonolithPaths]. May include API path segments, e.g. `https://host/api/shopify-service/v1` with no trailing slash. */
  val monolithBaseUrl: String?,

  /**
   * Optional path inserted after [monolithBaseUrl]: `{base}/{prefix}` + [MonolithPaths.STORES_API_KEY].
   * Set via `MONOLITH_API_PREFIX` (e.g. `api/v1`); omit slashes at both ends or they are trimmed.
   */
  val monolithApiPrefix: String?,

  /** Sent as `Authorization: Bearer …` when non-blank. */
  val monolithApiKey: String?,

  /** Path appended to monolith base for order creation (default [MonolithPaths.ORDERS]). */
  val monolithCreateOrderPath: String,

  /** When set, DSS REST routes require header `X-DSS-Internal-Secret` (except health/install/oauth/webhooks). */
  val dssInternalSecret: String?,

  /**
   * Normalized `shop.myshopify.com` → Admin API access token. No server-side database; callers pass
   * `X-Shopify-Access-Token` or configure this map via env (see [shopAccessTokensFromEnv]).
   * Backed by a [ConcurrentHashMap] so OAuth callbacks, webhook handlers, and the monolith-fallback
   * token resolver can all write concurrently without data races.
   */
  val shopAccessTokens: MutableMap<String, String>,

  val enableDemoRoutes: Boolean,

  /** When true, serve the HTML test harness at `GET` [DssPaths.DEV_TEST_HARNESS] (see `ENABLE_TEST_HARNESS`). */
  val enableTestHarness: Boolean,

  /**
   * When true (only with [enableTestHarness]), DSS + demo skip real Shopify calls and return stub 200/OK
   * so the HTML harness can be exercised without a real token. See `DSS_SANDBOX_FAKE_SHOPIFY`.
   */
  val sandboxFakeShopify: Boolean,

  /** When false (default), `MONOLITH_BASE_URL` must be `https://` (except unset). */
  val allowInsecureMonolithUrl: Boolean,

  /** When true, `POST` to [MonolithPaths.ORDERS] on `orders/updated` as well as `orders/create` (default false). */
  val syncOrderOnUpdated: Boolean,
) {
  companion object {
    fun fromEnv(): DssAppConfig? {
      val shopify = ShopifyConfig.fromEnv() ?: error(
        "Set env vars: SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY), SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET), SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
      val base = EnvVars.optionalNormalized("MONOLITH_BASE_URL")
      val key = EnvVars.optionalNormalized("MONOLITH_API_KEY")
      val prefixRaw = EnvVars.optionalNormalized("MONOLITH_API_PREFIX")
      val prefix = prefixRaw?.trim()?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
      val rawPath = EnvVars.optionalNormalized("MONOLITH_CREATE_ORDER_PATH") ?: MonolithPaths.ORDERS
      val path = when {
        rawPath.startsWith('/') -> rawPath
        else -> "/$rawPath"
      }
      val secret = EnvVars.optionalNormalized("DSS_INTERNAL_SECRET")
      val testHarness =
        System.getenv("ENABLE_TEST_HARNESS")?.trim()?.equals("true", ignoreCase = true) == true
      val demosExplicit =
        System.getenv("ENABLE_DEMO_ROUTES")?.trim()?.equals("true", ignoreCase = true) == true
      val demos = demosExplicit || testHarness
      val allowInsecure = System.getenv("DSS_ALLOW_INSECURE_MONOLITH")?.trim()
        ?.equals("true", ignoreCase = true) == true
      val fakeShopify = testHarness &&
        System.getenv("DSS_SANDBOX_FAKE_SHOPIFY")?.trim()
          ?.equals("true", ignoreCase = true) == true
      val syncOnUpdated = System.getenv("DSS_SYNC_ORDER_ON_UPDATED")?.trim()
        ?.equals("true", ignoreCase = true) == true
      if (base != null && base.startsWith("http:", ignoreCase = true) && !allowInsecure) {
        error("MONOLITH_BASE_URL must use https. For local set DSS_ALLOW_INSECURE_MONOLITH=true")
      }

      val dssConfig = DssAppConfig(
        shopify = shopify,
        monolithBaseUrl = base,
        monolithApiPrefix = prefix,
        monolithApiKey = key,
        monolithCreateOrderPath = path,
        dssInternalSecret = secret,
        shopAccessTokens = ConcurrentHashMap(shopAccessTokensFromEnv(enableTestHarness = testHarness)),
        enableDemoRoutes = demos,
        enableTestHarness = testHarness,
        sandboxFakeShopify = fakeShopify,
        allowInsecureMonolithUrl = allowInsecure,
        syncOrderOnUpdated = syncOnUpdated,
      )

      val configIssues = computeRuntimeConfigIssues(dssConfig)
      if (configIssues.isNotEmpty()) {
        error("Missing/invalid config: ${configIssues.joinToString()}")
      }

      return dssConfig
    }
  }
}



private fun computeRuntimeConfigIssues(dssConfig: DssAppConfig): List<String> {
  val config = dssConfig.shopify
  val issues = mutableListOf<String>()

  if (isPlaceholder(config.appClientId)) {
    issues += "SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY) is placeholder"
  }
  if (isPlaceholder(config.appClientSecret)) {
    issues += "SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET) is placeholder"
  }
  if (isPlaceholder(config.publicBaseUrl) || config.publicBaseUrl.contains("example.com", ignoreCase = true)) {
    issues += "PUBLIC_BASE_URL is placeholder"
  }
  if (!config.publicBaseUrl.startsWith("https://", ignoreCase = true)) {
    issues += "PUBLIC_BASE_URL should use https://"
  }
  if (dssConfig.dssInternalSecret?.contains("change_this") == true) {
    issues += "DSS_INTERNAL_SECRET is still the placeholder value — set a real secret"
  }
  if (dssConfig.dssInternalSecret != null && dssConfig.dssInternalSecret.length < 32) {
    issues += "DSS_INTERNAL_SECRET should be at least 32 characters"
  }

  return issues
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)
