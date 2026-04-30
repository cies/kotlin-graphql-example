package dropnext.dss.shopify

import dropnext.dss.config.ShopifyConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

fun buildOAuthAuthorizeUrl(shop: String, config: ShopifyConfig, state: String): String {
  val enc: (String) -> String = { URLEncoder.encode(it, StandardCharsets.UTF_8) }
  return buildString {
    append("https://")
    append(shop)
    append("/admin/oauth/authorize?client_id=")
    append(enc(config.apiKey))
    append("&scope=")
    append(enc(config.scopes))
    append("&redirect_uri=")
    append(enc(config.redirectUrl))
    append("&state=")
    append(enc(state))
  }
}

fun randomOAuthState(): String {
  val bytes = ByteArray(16)
  SecureRandom().nextBytes(bytes)
  return bytes.joinToString("") { "%02x".format(it) }
}
