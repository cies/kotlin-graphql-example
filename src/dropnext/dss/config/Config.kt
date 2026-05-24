package dropnext.dss.config

import dropnext.dss.path.Paths


/**
 * Top-level effective configuration.
 * Composed of per-concern groups (shopify, monolith, dev, webhook),
 * so each section owns its own [fromEnv] and tests;
 * [Config.fromEnv] just stitches them together and validates the result.
 */
data class Config(
  val shopify: ShopifyConfig,
  val monolith: MonolithConfig,
  val dev: DevConfig,
  val webhook: WebhookConfig,

  /**
   * When set, monolith-webhook routes (see [dropnext.dss.routing.installMonolithWebhookRoutes])
   * require header `X-DSS-Internal-Secret`. Sourced from env `DSS_INTERNAL_SECRET`. Other
   * inbound routes (health, install, oauth callback, Shopify webhooks, demo) ignore this value.
   */
  val monolithWebhookAuthSecret: String?,
) {
  companion object {
    fun fromEnv(): Config {
      val shopify = ShopifyConfig.fromEnv() ?: error(
        "Set env vars: SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY), SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET), SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
      val monolith = MonolithConfig.fromEnv() ?: error(
        "Set env var MONOLITH_BASE_URL (point it at a stub URL if you do not have a real DropNext monolith running).",
      )
      val dev = DevConfig.fromEnv()

      val dssConfig = Config(
        shopify = shopify,
        monolith = monolith,
        dev = dev,
        webhook = WebhookConfig.fromEnv(),
        monolithWebhookAuthSecret = EnvVars.optionalNormalized("DSS_INTERNAL_SECRET"),
      )

      val configIssues = computeRuntimeConfigIssues(dssConfig)
      if (configIssues.isNotEmpty()) {
        error("Missing/invalid config: ${configIssues.joinToString()}")
      }

      return dssConfig
    }
  }
}


/** Outbound monolith integration: where to reach it and how to authenticate. */
data class MonolithConfig(
  /** HTTPS base URL for monolith outbound calls; DSS appends paths from [OutBoundMonolithPaths]. */
  val baseUrl: String,

  /**
   * Optional path segment inserted after [baseUrl] and prepended to every outbound monolith path
   * (see [OutBoundMonolithPaths]): the effective URL is `{base}/{prefix}{path}`.
   * Set via `MONOLITH_API_PREFIX` (e.g. `api/v1`); slashes at both ends are trimmed.
   */
  val apiPrefix: String?,

  /** Sent as `Authorization: Bearer …` when non-blank. */
  val apiKey: String?,

  /** When false (default), [baseUrl] must be `https://`. */
  val allowInsecureUrl: Boolean,
) {
  companion object {
    fun fromEnv(): MonolithConfig? {
      val baseUrl = EnvVars.optionalNormalized("MONOLITH_BASE_URL") ?: return null
      val prefix = EnvVars.optionalNormalized("MONOLITH_API_PREFIX")
        ?.trim { it == '/' }?.takeIf { it.isNotEmpty() }
      return MonolithConfig(
        baseUrl = baseUrl,
        apiPrefix = prefix,
        apiKey = EnvVars.optionalNormalized("MONOLITH_API_KEY"),
        allowInsecureUrl = EnvVars.optionalBool("DSS_ALLOW_INSECURE_MONOLITH"),
      )
    }
  }
}


/** Dev / demo / sandbox switches. Keep off in production. */
// TODO: replace with one [APP_MODE], and remove what can be removed
data class DevConfig(
  val enableDemoRoutes: Boolean,

  /** When true, the sandbox token map is merged and the `/demo/` routes are forced on (see `ENABLE_TEST_HARNESS`). */
  val enableTestHarness: Boolean,
) {
  companion object {
    fun fromEnv(): DevConfig {
      val testHarness = EnvVars.optionalBool("ENABLE_TEST_HARNESS")
      return DevConfig(
        enableDemoRoutes = EnvVars.optionalBool("ENABLE_DEMO_ROUTES") || testHarness,
        enableTestHarness = testHarness,
      )
    }
  }
}


/** Webhook policy switches. */
data class WebhookConfig(
  /** When true, `POST` to [OutBoundMonolithPaths.ORDERS] on `orders/updated` as well as `orders/create` (default false). */
  // TODO: what is the use of this? the comment does not explain why.
  val syncOrderOnUpdated: Boolean,
) {
  companion object {
    fun fromEnv(): WebhookConfig = WebhookConfig(
      syncOrderOnUpdated = EnvVars.optionalBool("DSS_SYNC_ORDER_ON_UPDATED"),
    )
  }
}


internal fun computeRuntimeConfigIssues(dssConfig: Config): List<String> = buildList {
  val shopify = dssConfig.shopify
  if (isPlaceholder(shopify.appClientId)) {
    add("SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY) is placeholder")
  }
  if (isPlaceholder(shopify.appClientSecret)) {
    add("SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET) is placeholder")
  }
  if (isPlaceholder(shopify.publicBaseUrl) || shopify.publicBaseUrl.contains("example.com", ignoreCase = true)) {
    add("PUBLIC_BASE_URL is placeholder")
  }
  if (!shopify.publicBaseUrl.startsWith("https://", ignoreCase = true)) {
    add("PUBLIC_BASE_URL should use https://")
  }
  if (dssConfig.monolithWebhookAuthSecret?.contains("change_this") == true) {
    add("DSS_INTERNAL_SECRET is still the placeholder value — set a real secret")
  }
  if (dssConfig.monolithWebhookAuthSecret != null && dssConfig.monolithWebhookAuthSecret.length < 32) {
    add("DSS_INTERNAL_SECRET should be at least 32 characters")
  }
  val monolithBaseUrl = dssConfig.monolith.baseUrl
  if (monolithBaseUrl.startsWith("http:", ignoreCase = true) && !dssConfig.monolith.allowInsecureUrl) {
    add("MONOLITH_BASE_URL must use https (set DSS_ALLOW_INSECURE_MONOLITH=true for local dev)")
  }
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)


data class ShopifyConfig(
  val appClientId: String,
  val appClientSecret: String,
  val scopes: String,
  val publicBaseUrl: String,
  val oauthRedirectPath: String,
  val apiVersion: String,
  val serverPort: Int,
) {
  val redirectUrl: String
    get() = publicBaseUrl.trimEnd('/') + oauthRedirectPath

  companion object {
    fun fromEnv(): ShopifyConfig? {
      val appClientId = env("SHOPIFY_APP_CLIENT_ID") ?: env("SHOPIFY_API_KEY")
      val appClientSecret = env("SHOPIFY_APP_CLIENT_SECRET") ?: env("SHOPIFY_API_SECRET")
      val scopes = env("SHOPIFY_SCOPES")
      val publicBaseUrl = env("PUBLIC_BASE_URL")

      if (appClientId == null || appClientSecret == null || scopes == null || publicBaseUrl == null) return null

      return ShopifyConfig(
        appClientId = appClientId,
        appClientSecret = appClientSecret,
        scopes = scopes,
        publicBaseUrl = publicBaseUrl.trimEnd('/'),
        oauthRedirectPath = (env("OAUTH_REDIRECT_PATH") ?: "")
          .ifBlank { Paths.defaultOAuthCallback },
        apiVersion = (env("SHOPIFY_API_VERSION") ?: "").ifBlank { "2026-04" },
        serverPort = resolveServerPort(),
      )
    }

    /**
     * PaaS UIs sometimes define `PORT=` (empty string), wiping Docker `ENV PORT=9999`;
     * that makes Ktor bind 8080 while Traefik/nginx still proxies 9999 → **502 Bad Gateway**.
     * Empty → use prod image default 9999;
     * unset PORT → **8080** for local `./gradlew run` without env.
     */
    private fun resolveServerPort(): Int = resolveServerPort(
      System.getenv("PORT"),
      EnvVars.optionalNormalized("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 },
    )

    /** Pure decision extracted for unit testing; see [resolveServerPort] for the env-bound caller. */
    internal fun resolveServerPort(raw: String?, parsed: Int?): Int = when {
      parsed != null -> parsed
      raw == null -> 8080
      raw.isBlank() -> 9999
      else -> 8080
    }

    private fun env(name: String): String? = EnvVars.optionalNormalized(name)
  }
}
