package dropnext.dss.config

import dropnext.dss.path.DssPaths


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
        oauthRedirectPath = (env("OAUTH_REDIRECT_PATH") ?: "").ifBlank { DssPaths.DEFAULT_OAUTH_CALLBACK },
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
    private fun resolveServerPort(): Int =
      resolveServerPort(
        System.getenv("PORT"),
        EnvVars.optionalNormalized("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 },
      )

    /** Pure decision extracted for unit testing; see [resolveServerPort] for the env-bound caller. */
    internal fun resolveServerPort(raw: String?, parsed: Int?): Int =
      when {
        parsed != null -> parsed
        raw == null -> 8080
        raw.isBlank() -> 9999
        else -> 8080
      }

    private fun env(name: String): String? = EnvVars.optionalNormalized(name)
  }
}
