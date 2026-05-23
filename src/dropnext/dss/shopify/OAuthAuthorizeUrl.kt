package dropnext.dss.shopify

import dropnext.dss.config.ShopifyConfig
import dropnext.dss.path.ShopifyPaths
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom


// TODO: possibly make an OAuthAuthorizer service
fun buildOAuthAuthorizeUrl(shop: String, config: ShopifyConfig, state: String): String {
  val enc: (String) -> String = { URLEncoder.encode(it, StandardCharsets.UTF_8) }
  return buildString {
    append("https://")
    append(shop)
    append(ShopifyPaths.ADMIN_OAUTH_AUTHORIZE)
    append("?client_id=")
    append(enc(config.appClientId))
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
