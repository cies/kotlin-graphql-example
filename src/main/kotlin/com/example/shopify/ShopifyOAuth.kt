package com.example.lib.shopify

import com.example.lib.shopify.ShopifyConfig
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
): OAuthAccessTokenResponse {
  val url = "https://$shop/admin/oauth/access_token"
  return httpClient.post(url) {
    contentType(ContentType.Application.Json)
    setBody(
      OAuthAccessTokenRequest(
        clientId = config.apiKey,
        clientSecret = config.apiSecret,
        code = code,
      ),
    )
  }.body()
}
