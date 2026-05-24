package dropnext.dss.lib.shopify.oauth

import dropnext.dss.config.ShopifyConfig
import dropnext.dss.path.ShopifyPaths
import dropnext.dss.lib.shopify.ShopDomain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class OAuthAccessTokenResponse(
  @SerialName("access_token")
  val accessToken: String,

  @SerialName("scope")
  val scope: String,
)

/**
 * Per-app Shopify OAuth client. Encapsulates the three pre-token OAuth operations the install
 * flow needs:
 *
 *  - [authorizeUrl] — the URL we redirect a merchant to so they grant the app access;
 *  - [signedState] / [isSignedStateValid] — short-lived HMAC-signed `state` parameter to defend
 *    the install endpoint against CSRF and replay;
 *  - [exchangeCode] — trades the authorization `code` Shopify returns at the redirect for a
 *    permanent Admin API access token.
 *
 * Stateless aside from the injected [HttpClient] and [ShopifyConfig], so a single instance is
 * safe to share across requests.
 */
class ShopifyOAuthClient(
  private val httpClient: HttpClient,
  private val config: ShopifyConfig,
) {
  /** Builds the OAuth authorize redirect URL for [shop] with a signed [state]. */
  fun authorizeUrl(shop: ShopDomain, state: String): String {
    val enc: (String) -> String = { URLEncoder.encode(it, StandardCharsets.UTF_8) }
    return buildString {
      append("https://")
      append(shop.host)
      append(ShopifyPaths.ADMIN_OAUTH_AUTHORIZE)
      append("?client_id=").append(enc(config.appClientId))
      append("&scope=").append(enc(config.scopes))
      append("&redirect_uri=").append(enc(config.redirectUrl))
      append("&state=").append(enc(state))
    }
  }

  /**
   * Returns a freshly signed `shop|expiry|nonce|hmac` state token. The expiry pins the validity
   * window so a leaked redirect cannot be replayed after [OAUTH_STATE_TTL_SECONDS].
   */
  fun signedState(shop: ShopDomain, now: Instant = Instant.now()): String {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val noncePart = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    val expiresAt = now.epochSecond + OAUTH_STATE_TTL_SECONDS
    val payload = "${shop.host}|$expiresAt|$noncePart"
    val signature = hmacSha256Base64Url(config.appClientSecret, payload)
    return "$payload|$signature"
  }

  /** Verifies that [state] was produced by [signedState] for [expectedShop] and has not expired. */
  fun isSignedStateValid(
    state: String,
    expectedShop: ShopDomain,
    now: Instant = Instant.now(),
  ): Boolean {
    val parts = state.split('|')
    if (parts.size != 4) return false
    val shop = parts[0]
    val expiresAt = parts[1].toLongOrNull() ?: return false
    val nonce = parts[2]
    val signature = parts[3]
    if (shop != expectedShop.host) return false
    if (expiresAt < now.epochSecond) return false
    val payload = "$shop|$expiresAt|$nonce"
    val expected = hmacSha256Base64Url(config.appClientSecret, payload)
    return MessageDigest.isEqual(
      expected.toByteArray(StandardCharsets.UTF_8),
      signature.toByteArray(StandardCharsets.UTF_8),
    )
  }

  /**
   * Trades the authorization [code] Shopify returned at the OAuth redirect for a long-lived
   * Admin API access token. Wraps the HTTP call in [runCatching] so callers can fail closed
   * without try/catch.
   */
  suspend fun exchangeCode(shop: ShopDomain, code: String): Result<OAuthAccessTokenResponse> =
    runCatching {
      val url = "https://${shop.host}${ShopifyPaths.ADMIN_OAUTH_ACCESS_TOKEN}"
      httpClient.post(url) {
        contentType(ContentType.Application.Json)
        setBody(
          OAuthAccessTokenRequest(
            clientId = config.appClientId,
            clientSecret = config.appClientSecret,
            code = code,
          ),
        )
      }.body<OAuthAccessTokenResponse>()
    }

  private fun hmacSha256Base64Url(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)))
  }

  companion object {
    /** TTL for the OAuth `state` parameter. Five minutes is plenty for a single install round-trip. */
    const val OAUTH_STATE_TTL_SECONDS = 300L

    /** Returns 32 hex characters for use as the legacy unsigned OAuth `state` parameter (tests only). */
    fun randomState(): String {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}

@Serializable
private data class OAuthAccessTokenRequest(
  @SerialName("client_id")
  val clientId: String,

  @SerialName("client_secret")
  val clientSecret: String,

  @SerialName("code")
  val code: String,
)
