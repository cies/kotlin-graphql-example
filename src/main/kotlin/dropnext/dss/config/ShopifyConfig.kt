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
        oauthRedirectPath = env("OAUTH_REDIRECT_PATH") ?: "/oauth/callback",
        apiVersion = env("SHOPIFY_API_VERSION") ?: "2026-04",
        serverPort = env("PORT")?.toIntOrNull() ?: 8080,
      )
    }

    private fun env(name: String): String? =
      System.getenv(name)?.takeIf { it.isNotBlank() }
  }
}
