package dropnext.dss.config

import dropnext.dss.path.Paths


data class Config(
  val appClientId: String,
  val appClientSecret: String,
  val scopes: String,
  val dssBaseUrl: String,
  val oauthRedirectPath: String,
  val apiVersion: String,
  val serverPort: Int,
  val monolithBaseUrl: String,
  val monolithApiPrefix: String?,
  val monolithApiKey: String?,
  val allowInsecureMonolithUrl: Boolean,
  val monolithWebhookAuthSecret: String,
) {
  val redirectUrl: String
    get() = dssBaseUrl.trimEnd('/') + oauthRedirectPath

  companion object {
    fun fromEnv(): Config {
      val appClientId = env("SHOPIFY_APP_CLIENT_ID") ?: env("SHOPIFY_API_KEY")
        ?: error("Set env var SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY).")
      val appClientSecret = env("SHOPIFY_APP_CLIENT_SECRET") ?: env("SHOPIFY_API_SECRET")
        ?: error("Set env var SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET).")
      val scopes = env("SHOPIFY_SCOPES")
        ?: error("Set env var SHOPIFY_SCOPES.")
      val dssBaseUrl = env("DSS_BASE_URL")
        ?: error("Set env var DSS_BASE_URL.")
      val monolithBaseUrl = optionalNormalized("MONOLITH_BASE_URL")
        ?: error("Set env var MONOLITH_BASE_URL.")
      val monolithWebhookAuthSecret = optionalNormalized("DSS_API_KEY")
        ?: error("Set env var DSS_API_KEY.")

      if (isPlaceholder(appClientId)) {
        error("SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY) is placeholder.")
      }
      if (isPlaceholder(appClientSecret)) {
        error("SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET) is placeholder.")
      }
      if (isPlaceholder(dssBaseUrl) || dssBaseUrl.contains("example.com", ignoreCase = true)) {
        error("DSS_BASE_URL is placeholder.")
      }
      if (!dssBaseUrl.startsWith("https://", ignoreCase = true)) {
        error("DSS_BASE_URL should use https://.")
      }
      if (monolithWebhookAuthSecret.contains("change_this", ignoreCase = true)) {
        error("DSS_API_KEY is still a placeholder value.")
      }
      if (monolithWebhookAuthSecret.length < 32) {
        error("DSS_API_KEY must be at least 32 characters.")
      }

      val allowInsecureMonolithUrl = optionalBool("DSS_ALLOW_INSECURE_MONOLITH")
      if (monolithBaseUrl.startsWith("http:", ignoreCase = true) && !allowInsecureMonolithUrl) {
        error("MONOLITH_BASE_URL must use https:// (or set DSS_ALLOW_INSECURE_MONOLITH=true for local dev).")
      }

      val monolithApiPrefix = optionalNormalized("MONOLITH_API_PREFIX")
        ?.trim { it == '/' }
        ?.takeIf { it.isNotEmpty() }

      return Config(
        appClientId = appClientId,
        appClientSecret = appClientSecret,
        scopes = scopes,
        dssBaseUrl = dssBaseUrl.trimEnd('/'),
        oauthRedirectPath = (env("OAUTH_REDIRECT_PATH") ?: "").ifBlank { Paths.defaultOAuthCallback },
        apiVersion = (env("SHOPIFY_API_VERSION") ?: "").ifBlank { "2026-04" },
        serverPort = resolveServerPort(),
        monolithBaseUrl = monolithBaseUrl,
        monolithApiPrefix = monolithApiPrefix,
        monolithApiKey = optionalNormalized("MONOLITH_API_KEY"),
        allowInsecureMonolithUrl = allowInsecureMonolithUrl,
        monolithWebhookAuthSecret = monolithWebhookAuthSecret,
      )
    }
    /**
     * PaaS UIs sometimes define `PORT=` (empty string), wiping Docker `ENV PORT=9999`;
     * that makes Ktor bind 8080 while Traefik/nginx still proxies 9999 -> 502 Bad Gateway.
     * Empty -> use prod image default 9999;
     * unset PORT -> 8080 for local `./gradlew run` without env.
     */
    private fun resolveServerPort(): Int = resolveServerPort(
      System.getenv("PORT"),
      optionalNormalized("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 },
    )

    /** Pure decision extracted for unit testing; see [resolveServerPort] for the env-bound caller. */
    internal fun resolveServerPort(raw: String?, parsed: Int?): Int = when {
      parsed != null -> parsed
      raw == null -> 8080
      raw.isBlank() -> 9999
      else -> 8080
    }

    private fun env(name: String): String? = optionalNormalized(name)
  }
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)
