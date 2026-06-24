package dropnext.dss.config

import kotlin.test.Test

class ShopifyConfigTest {

  @Test
  fun `parsed valid port wins`() {
    assert(Config.resolveServerPort(raw = "9999", parsed = 9999) == 9999)
  }

  @Test
  fun `PORT unset returns 8080`() {
    assert(Config.resolveServerPort(raw = null, parsed = null) == 8080)
  }

  @Test
  fun `PORT set but blank returns 9999`() {
    assert(Config.resolveServerPort(raw = "", parsed = null) == 9999)
    assert(Config.resolveServerPort(raw = "   ", parsed = null) == 9999)
  }

  @Test
  fun `PORT set but unparseable returns 8080`() {
    assert(Config.resolveServerPort(raw = "not-a-number", parsed = null) == 8080)
  }

  @Test
  fun `redirectUrl trims trailing slash from dssBaseUrl`() {
    val config = Config(
      appClientId = "id",
      appClientSecret = "secret",
      scopes = "read_orders",
      dssBaseUrl = "https://dss.example.com",
      oauthRedirectPath = "/oauth/callback",
      apiVersion = "2026-04",
      serverPort = 8080,
      monolithBaseUrl = "https://monolith.example.com",
      monolithApiPrefix = null,
      monolithApiKey = null,
      allowInsecureMonolithUrl = false,
      monolithWebhookAuthSecret = "x".repeat(32),
    )
    assert(config.redirectUrl == "https://dss.example.com/oauth/callback")
  }
}
