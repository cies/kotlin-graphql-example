package dropnext.dss.lib.shopify.oauth

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.crypto.constantTimeEquals
import dropnext.dss.lib.crypto.hmacSha256
import dropnext.dss.lib.shopify.graphql.ShopifyError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** TTL for the OAuth `state` parameter. Five minutes is plenty for a single installation round-trip. */
private const val OAUTH_STATE_TTL_SECONDS = 300L

/**
 * Per-app Shopify OAuth client. Encapsulates the three pre-token OAuth operations the installation flow needs:
 *
 *  - [authorizeUrl] — the URL we redirect a merchant to so they grant the app access;
 *  - [signedState] / [isSignedStateValid] — short-lived HMAC-signed `state` parameter to defend
 *    the installation endpoint against CSRF and replay;
 *  - [exchangeCode] — trades the authorization `code` Shopify returns at the redirect for a
 *    permanent Admin API access token.
 *
 * Stateless aside from the injected [HttpClient] and the app's credentials, so a single instance
 * is safe to share across requests.
 */
class ShopifyOAuthService(
  private val httpClient: HttpClient,
  private val clientId: String,
  private val clientSecret: ShopifyAppSecret,
  private val scopes: String,
  private val redirectUrl: String,
) {
  /** Builds the OAuth authorize redirect URL for [shop] with a signed [state]. */
  fun authorizeUrl(shop: ShopDomain, state: String): String {
    val enc: (String) -> String = { URLEncoder.encode(it, StandardCharsets.UTF_8) }
    return buildString {
      append("https://")
      append(shop.normalizedShopifyHost)
      append(OutBoundShopifyOAuthPaths.adminOAuthAuthorize)
      append("?client_id=").append(enc(clientId))
      append("&scope=").append(enc(scopes))
      append("&redirect_uri=").append(enc(redirectUrl))
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
    val payload = "${shop.normalizedShopifyHost}|$expiresAt|$noncePart"
    return "$payload|${sign(payload)}"
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
    if (shop != expectedShop.normalizedShopifyHost) return false
    if (expiresAt < now.epochSecond) return false
    return constantTimeEquals(sign("$shop|$expiresAt|$nonce"), signature)
  }

  /**
   * Trades the authorization [code] Shopify returned at the OAuth redirect for a long-lived Admin API access token.
   * Any failure of the exchange (transport, a non-2xx, an unreadable body) is a [ShopifyError.Network]:
   * the installation cannot continue either way.
   */
  suspend fun exchangeCode(shop: ShopDomain, code: String): Result<ShopifyAdminToken, ShopifyError> {
    val url = "https://${shop.normalizedShopifyHost}${OutBoundShopifyOAuthPaths.adminOAuthAccessToken}"
    val response = try {
      httpClient.post(url) {
        contentType(ContentType.Application.Json)
        setBody(OAuthAccessTokenRequest(clientId = clientId, clientSecret = clientSecret.value, code = code))
      }.body<OAuthAccessTokenResponse>()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Failure(ShopifyError.Network(e.message ?: "OAuth code exchange failed"))
    }
    return Success(ShopifyAdminToken(response.accessToken))
  }

  private fun sign(payload: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(clientSecret.value, payload))
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

/** The wire shape of Shopify's token response; the token is wrapped in a [ShopifyAdminToken] as soon as it is decoded. */
@Serializable
private data class OAuthAccessTokenResponse(
  @SerialName("access_token")
  val accessToken: String,

  @SerialName("scope")
  val scope: String,
)
