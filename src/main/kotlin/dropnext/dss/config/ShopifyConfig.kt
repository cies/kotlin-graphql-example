package dropnext.dss.config

data class ShopifyConfig(
  val apiKey: String,
  val apiSecret: String,
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
      val key = env("SHOPIFY_APP_CLIENT_ID") ?: env("SHOPIFY_API_KEY")
      val secret = env("SHOPIFY_APP_CLIENT_SECRET") ?: env("SHOPIFY_API_SECRET")
      val scopes = env("SHOPIFY_SCOPES")
      val base = env("PUBLIC_BASE_URL")
      if (key == null || secret == null || scopes == null || base == null) return null
      return ShopifyConfig(
        apiKey = key,
        apiSecret = secret,
        scopes = scopes,
        publicBaseUrl = base.trimEnd('/'),
        oauthRedirectPath = (env("OAUTH_REDIRECT_PATH") ?: "").ifBlank { "/oauth/callback" },
        apiVersion = (env("SHOPIFY_API_VERSION") ?: "").ifBlank { "2026-04" },
        serverPort = resolveServerPort(),
      )
    }

    /**
     * PaaS UIs sometimes define `PORT=` (empty string), wiping Docker `ENV PORT=9999`; that makes Ktor bind
     * 8080 while Traefik/nginx still proxies 9999 → **502 Bad Gateway**. Empty → use prod image default 9999;
     * unset PORT → **8080** for local `./gradlew run` without env.
     */
    private fun resolveServerPort(): Int {
      val raw = System.getenv("PORT")
      val parsed = EnvVars.optionalNormalized("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 }
      return when {
        parsed != null -> parsed
        raw == null -> 8080
        raw.isBlank() -> 9999
        else -> 8080
      }
    }

    private fun env(name: String): String? =
      EnvVars.optionalNormalized(name)
  }
}
