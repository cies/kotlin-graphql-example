package dropnext.dss.config

import kotlin.test.Test

class ConfigTest {

  private fun appConfig(
    dssBaseUrl: String = "https://dss.example.org",
  ): Config =
    Config(
      appClientId = "good-id",
      appClientSecret = "good-secret",
      scopes = "read_orders",
      dssBaseUrl = dssBaseUrl,
      oauthRedirectPath = "/oauth/callback",
      apiVersion = "2026-04",
      serverPort = 8080,
      monolithBaseUrl = "https://monolith.example.org",
      monolithApiPrefix = null,
      monolithApiKey = null,
      allowInsecureMonolithUrl = false,
      monolithWebhookAuthSecret = "x".repeat(32),
    )

  @Test
  fun `redirectUrl trims trailing slash from dssBaseUrl`() {
    val config = appConfig(dssBaseUrl = "https://dss.example.org/")
    assert(config.redirectUrl == "https://dss.example.org/oauth/callback")
  }
}
