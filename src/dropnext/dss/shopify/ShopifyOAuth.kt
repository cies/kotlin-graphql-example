package dropnext.dss.shopify

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
  @SerialName(value = "client_id") val clientId: String,
  @SerialName(value = "client_secret") val clientSecret: String,
  @SerialName(value = "code") val code: String,
)

@Serializable
data class OAuthAccessTokenResponse(
  @SerialName(value = "access_token") val accessToken: String,
  @SerialName(value = "scope") val scope: String,
)

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
