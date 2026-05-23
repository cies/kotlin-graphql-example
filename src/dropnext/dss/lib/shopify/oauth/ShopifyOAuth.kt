package dropnext.dss.lib.shopify.oauth

import dropnext.dss.config.ShopifyConfig
import dropnext.dss.path.ShopifyPaths
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
private data class OAuthAccessTokenRequest(
  @SerialName("client_id")
  val clientId: String,

  @SerialName("client_secret")
  val clientSecret: String,

  @SerialName("code")
  val code: String,
)

@Serializable
data class OAuthAccessTokenResponse(
  @SerialName("access_token")
  val accessToken: String,

  @SerialName("scope")
  val scope: String,
)

// TODO(cies): Seems this is an appropriate candidate for a service (shopify.json)
suspend fun exchangeAuthorizationCode(
  httpClient: HttpClient,
  shop: String,
  code: String,
  config: ShopifyConfig,
): Result<OAuthAccessTokenResponse> =
  runCatching {
    val url = "https://$shop${ShopifyPaths.ADMIN_OAUTH_ACCESS_TOKEN}"
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
