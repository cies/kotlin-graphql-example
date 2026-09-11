package dropnext.dss.config

import dropnext.dss.domain.DssApiKey
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.domain.MonolithApiKey
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.path.Paths


/**
 * Every environment variable the service reads, read once in [from]. The README's variable table
 * documents this class; a variable not read here is not a variable.
 */
data class Config(
  val appClientId: String,
  val appClientSecret: ShopifyAppSecret,
  val scopes: String,
  val dssBaseUrl: String,
  val oauthRedirectPath: String,
  val apiVersion: String,
  val serverPort: Int,
  val monolithBaseUrl: String,
  val monolithApiPrefix: String?,
  val monolithApiKey: MonolithApiKey?,
  val allowInsecureMonolithUrl: Boolean,
  val dssApiKey: DssApiKey,
  val shopAccessTokens: Map<ShopDomain, ShopifyAdminToken>,
  val logflareSourceName: String?,
  val logflareApiKey: LogflareApiKey?,
  val logflareEndpoint: String?,
  val mode: DssMode,
) {
  /** Log shipping needs both halves; with either missing the service logs to stdout only. */
  val logflareEnabled: Boolean
    get() = !logflareSourceName.isNullOrBlank() && logflareApiKey != null

  val redirectUrl: String
    get() = dssBaseUrl.trimEnd('/') + oauthRedirectPath

  companion object {
    /** The process environment, with a local `.env` file layered over it when present. */
    fun fromEnv(dotEnv: Map<String, String> = emptyMap()): Config = from(System.getenv() + dotEnv)

    /**
     * Builds the configuration from [env] and fails loud on anything missing or still a placeholder:
     * a service that starts on a half-filled template is worse than one that does not.
     */
    fun from(env: Map<String, String>): Config {
      fun value(name: String): String? = normalizeQuoted(env[name])
      fun required(name: String): String = value(name) ?: error("Set env var $name.")

      val appClientId = value("SHOPIFY_APP_CLIENT_ID") ?: value("SHOPIFY_API_KEY")
        ?: error("Set env var SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY).")
      val appClientSecret = value("SHOPIFY_APP_CLIENT_SECRET") ?: value("SHOPIFY_API_SECRET")
        ?: error("Set env var SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET).")
      val scopes = required("SHOPIFY_SCOPES")
      val dssBaseUrl = required("DSS_BASE_URL")
      val monolithBaseUrl = required("MONOLITH_BASE_URL")
      val dssApiKey = required("DSS_API_KEY")

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
      if (dssApiKey.contains("change_this", ignoreCase = true)) {
        error("DSS_API_KEY is still a placeholder value.")
      }
      if (dssApiKey.length < 32) {
        error("DSS_API_KEY must be at least 32 characters.")
      }

      val mode = DssMode.parse(env["DSS_MODE"])
      val allowInsecureMonolithUrl = parseBool(env["DSS_ALLOW_INSECURE_MONOLITH"])
      // The flag is a local-development escape hatch; `PROD` is the default mode, so a deployment that
      // sets the flag by mistake fails to boot instead of talking to the monolith in the clear.
      if (allowInsecureMonolithUrl && mode.isProd) {
        error("DSS_ALLOW_INSECURE_MONOLITH=true is for local development only: set DSS_MODE=DEV or drop the flag.")
      }
      if (monolithBaseUrl.startsWith("http:", ignoreCase = true) && !allowInsecureMonolithUrl) {
        error("MONOLITH_BASE_URL must use https:// (or set DSS_ALLOW_INSECURE_MONOLITH=true with DSS_MODE=DEV for local dev).")
      }

      return Config(
        appClientId = appClientId,
        appClientSecret = ShopifyAppSecret(appClientSecret),
        scopes = scopes,
        dssBaseUrl = dssBaseUrl.trimEnd('/'),
        oauthRedirectPath = value("OAUTH_REDIRECT_PATH") ?: Paths.defaultOAuthCallback,
        apiVersion = value("SHOPIFY_API_VERSION") ?: DEFAULT_SHOPIFY_API_VERSION,
        serverPort = resolveServerPort(env["PORT"]),
        monolithBaseUrl = monolithBaseUrl,
        monolithApiPrefix = value("MONOLITH_API_PREFIX")?.trim { it == '/' }?.takeIf { it.isNotEmpty() },
        monolithApiKey = value("MONOLITH_API_KEY")?.let(::MonolithApiKey),
        allowInsecureMonolithUrl = allowInsecureMonolithUrl,
        dssApiKey = DssApiKey(dssApiKey),
        shopAccessTokens = parseShopAccessTokens(value("DSS_SHOP_ACCESS_TOKENS")),
        logflareSourceName = value("LOGFLARE_SOURCE_NAME"),
        logflareApiKey = value("LOGFLARE_API_KEY")?.let(::LogflareApiKey),
        logflareEndpoint = value("LOGFLARE_ENDPOINT"),
        mode = mode,
      )
    }

    /** Must agree with the introspection endpoint in `build.gradle.kts`; the bump procedure is in `.claude/rules/graphql.md`. */
    const val DEFAULT_SHOPIFY_API_VERSION = "2026-04"

    /**
     * PaaS UIs sometimes define `PORT=` (empty string), wiping Docker `ENV PORT=9999`;
     * that makes Ktor bind 8080 while Traefik/nginx still proxies 9999 -> 502 Bad Gateway.
     * Empty -> use prod image default 9999;
     * unset PORT -> 8080 for local `./gradlew run` without env.
     */
    private fun resolveServerPort(raw: String?): Int {
      val parsed = normalizeQuoted(raw)?.toIntOrNull()?.takeIf { it in 1..65535 }
      return when {
        parsed != null -> parsed
        raw == null -> 8080
        raw.isBlank() -> 9999
        else -> 8080
      }
    }

    /** `DSS_SHOP_ACCESS_TOKENS`: comma-separated `shop|token` pairs; a malformed pair is skipped. */
    private fun parseShopAccessTokens(raw: String?): Map<ShopDomain, ShopifyAdminToken> =
      raw.orEmpty().split(',').mapNotNull { segment ->
        val part = segment.trim()
        val separator = part.indexOf('|')
        if (separator <= 0 || separator == part.length - 1) return@mapNotNull null
        val shop = ShopDomain.parse(part.substring(0, separator).trim()) ?: return@mapNotNull null
        shop to ShopifyAdminToken(part.substring(separator + 1).trim())
      }.toMap()
  }
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)

/**
 * An env value as a dotenv file or a PaaS dashboard may have left it:
 * trimmed and stripped of one or more layers of wrapping ASCII quotes.
 *
 * `null` when nothing is left.
 */
internal fun normalizeQuoted(raw: String?): String? {
  if (raw == null) return null
  var s = raw.trim()
  while (s.length >= 2) {
    val inner =
      when {
        s.startsWith('"') && s.endsWith('"') -> s.substring(1, s.length - 1).trim()
        s.startsWith('\'') && s.endsWith('\'') -> s.substring(1, s.length - 1).trim()
        else -> break
      }
    if (inner.isEmpty()) return null
    s = inner
  }
  return s.takeUnless { it.isEmpty() }
}

/** `true` only when the value resolves (after quote-stripping) to `true`, case-insensitive. */
internal fun parseBool(raw: String?): Boolean =
  normalizeQuoted(raw)?.equals("true", ignoreCase = true) == true
