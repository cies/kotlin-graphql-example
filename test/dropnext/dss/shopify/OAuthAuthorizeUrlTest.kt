package dropnext.dss.shopify

import dropnext.dss.config.ShopifyConfig
import kotlin.test.Test

class OAuthAuthorizeUrlTest {

  private val config =
    ShopifyConfig(
      appClientId = "client-id-123",
      appClientSecret = "secret",
      scopes = "read_orders,write_products",
      publicBaseUrl = "https://dss.example.com",
      oauthRedirectPath = "/oauth/callback",
      apiVersion = "2026-04",
      serverPort = 8080,
    )

  @Test
  fun `builds an authorize url with all required parameters`() {
    val url = buildOAuthAuthorizeUrl("acme.myshopify.com", config, "state-xyz")
    assert(url.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-123" in url)
    assert("scope=read_orders%2Cwrite_products" in url)
    assert("redirect_uri=https%3A%2F%2Fdss.example.com%2Foauth%2Fcallback" in url)
    assert("state=state-xyz" in url)
  }

  @Test
  fun `url-encodes special characters in state`() {
    val url = buildOAuthAuthorizeUrl("acme.myshopify.com", config, "a b|c")
    assert("state=a+b%7Cc" in url)
  }

  @Test
  fun `randomOAuthState returns 32 hex chars`() {
    val state = randomOAuthState()
    assert(state.length == 32)
    assert(state.all { it in '0'..'9' || it in 'a'..'f' })
  }

  @Test
  fun `randomOAuthState returns different values across calls`() {
    val a = randomOAuthState()
    val b = randomOAuthState()
    assert(a != b)
  }
}
