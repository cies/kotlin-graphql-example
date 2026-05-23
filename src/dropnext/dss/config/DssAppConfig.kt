package dropnext.dss.config

import dropnext.dss.path.MonolithPaths


data class DssAppConfig(

  val shopify: ShopifyConfig,

  /** HTTPS base URL for monolith outbound calls; DSS appends paths from [dropnext.dss.path.MonolithPaths]. May include API path segments, e.g. `https://host/api/shopify-service/v1` with no trailing slash. */
  val monolithBaseUrl: String?,

  /**
   * Optional path inserted after [monolithBaseUrl]: `{base}/{prefix}` + [dropnext.dss.path.MonolithPaths.STORES_API_KEY].
   * Set via `MONOLITH_API_PREFIX` (e.g. `api/v1`); omit slashes at both ends or they are trimmed.
   */
  val monolithApiPrefix: String?,

  /** Sent as `Authorization: Bearer …` when non-blank. */
  val monolithApiKey: String?,

  /** Path appended to monolith base for order creation (default [dropnext.dss.path.MonolithPaths.ORDERS]). */
  val monolithCreateOrderPath: String,

  /** When set, DSS REST routes require header `X-DSS-Internal-Secret` (except health/install/oauth/webhooks). */
  val dssInternalSecret: String?,

  val enableDemoRoutes: Boolean,

  /** When true, the sandbox token map is merged and the `/demo/` routes are forced on (see `ENABLE_TEST_HARNESS`). */
  val enableTestHarness: Boolean,

  /**
   * When true (only with [enableTestHarness]), DSS + demo skip real Shopify calls and return stub 200/OK
   * so the HTML harness can be exercised without a real token. See `DSS_SANDBOX_FAKE_SHOPIFY`.
   */
  val sandboxFakeShopify: Boolean,

  /** When false (default), `MONOLITH_BASE_URL` must be `https://` (except unset). */
  val allowInsecureMonolithUrl: Boolean,

  /** When true, `POST` to [dropnext.dss.path.MonolithPaths.ORDERS] on `orders/updated` as well as `orders/create` (default false). */
  val syncOrderOnUpdated: Boolean,
) {
  companion object {
    fun fromEnv(): DssAppConfig {
      val shopify = ShopifyConfig.fromEnv() ?: error(
        "Set env vars: SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY), SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET), SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
      val prefix = EnvVars.optionalNormalized("MONOLITH_API_PREFIX")
        ?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
      val rawPath = EnvVars.optionalNormalized("MONOLITH_CREATE_ORDER_PATH") ?: MonolithPaths.ORDERS
      val path = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
      val testHarness = EnvVars.optionalBool("ENABLE_TEST_HARNESS")

      val dssConfig = DssAppConfig(
        shopify = shopify,
        monolithBaseUrl = EnvVars.optionalNormalized("MONOLITH_BASE_URL"),
        monolithApiPrefix = prefix,
        monolithApiKey = EnvVars.optionalNormalized("MONOLITH_API_KEY"),
        monolithCreateOrderPath = path,
        dssInternalSecret = EnvVars.optionalNormalized("DSS_INTERNAL_SECRET"),
        enableDemoRoutes = EnvVars.optionalBool("ENABLE_DEMO_ROUTES") || testHarness,
        enableTestHarness = testHarness,
        sandboxFakeShopify = testHarness && EnvVars.optionalBool("DSS_SANDBOX_FAKE_SHOPIFY"),
        allowInsecureMonolithUrl = EnvVars.optionalBool("DSS_ALLOW_INSECURE_MONOLITH"),
        syncOrderOnUpdated = EnvVars.optionalBool("DSS_SYNC_ORDER_ON_UPDATED"),
      )

      val configIssues = computeRuntimeConfigIssues(dssConfig)
      if (configIssues.isNotEmpty()) {
        error("Missing/invalid config: ${configIssues.joinToString()}")
      }

      return dssConfig
    }
  }
}



internal fun computeRuntimeConfigIssues(dssConfig: DssAppConfig): List<String> {
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
  val monolithBaseUrl = dssConfig.monolithBaseUrl
  if (monolithBaseUrl != null &&
    monolithBaseUrl.startsWith("http:", ignoreCase = true) &&
    !dssConfig.allowInsecureMonolithUrl
  ) {
    issues += "MONOLITH_BASE_URL must use https (set DSS_ALLOW_INSECURE_MONOLITH=true for local dev)"
  }

  return issues
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)
