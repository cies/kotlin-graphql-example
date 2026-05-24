package dropnext.dss.config

import kotlin.test.Test

class ConfigTest {

  private fun shopify(
    appClientId: String = "good-id",
    appClientSecret: String = "good-secret",
    publicBaseUrl: String = "https://dss.example.org",
  ): ShopifyConfig =
    ShopifyConfig(
      appClientId = appClientId,
      appClientSecret = appClientSecret,
      scopes = "read_orders",
      publicBaseUrl = publicBaseUrl,
      oauthRedirectPath = "/oauth/callback",
      apiVersion = "2026-04",
      serverPort = 8080,
    )

  private fun appConfig(
    shopify: ShopifyConfig = shopify(),
    dssInternalSecret: String? = null,
    monolithBaseUrl: String = "https://monolith.example.org",
    allowInsecureMonolithUrl: Boolean = false,
  ): Config =
    Config(
      shopify = shopify,
      monolith = MonolithConfig(
        baseUrl = monolithBaseUrl,
        apiPrefix = null,
        apiKey = null,
        allowInsecureUrl = allowInsecureMonolithUrl,
      ),
      dev = DevConfig(
        enableDemoRoutes = false,
        enableTestHarness = false,
      ),
      webhook = WebhookConfig(syncOrderOnUpdated = false),
      monolithWebhookAuthSecret = dssInternalSecret,
    )

  @Test
  fun `clean config produces no issues`() {
    assert(computeRuntimeConfigIssues(appConfig()).isEmpty())
  }

  @Test
  fun `placeholder appClientId is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(shopify = shopify(appClientId = "your_id_here")))
    assert(issues.any { "SHOPIFY_APP_CLIENT_ID" in it })
  }

  @Test
  fun `placeholder appClientSecret is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(shopify = shopify(appClientSecret = "CHANGE_ME")))
    assert(issues.any { "SHOPIFY_APP_CLIENT_SECRET" in it })
  }

  @Test
  fun `publicBaseUrl containing example dot com is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(shopify = shopify(publicBaseUrl = "https://app.example.com")))
    assert(issues.any { "PUBLIC_BASE_URL is placeholder" in it })
  }

  @Test
  fun `publicBaseUrl with http is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(shopify = shopify(publicBaseUrl = "http://dss.local")))
    assert(issues.any { "PUBLIC_BASE_URL should use https" in it })
  }

  @Test
  fun `placeholder dssInternalSecret value is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(dssInternalSecret = "change_this_placeholder_now_xxxxxx"))
    assert(issues.any { "DSS_INTERNAL_SECRET is still the placeholder value" in it })
  }

  @Test
  fun `short dssInternalSecret is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(dssInternalSecret = "x".repeat(31)))
    assert(issues.any { "at least 32 characters" in it })
  }

  @Test
  fun `dssInternalSecret length 32 is accepted`() {
    val issues = computeRuntimeConfigIssues(appConfig(dssInternalSecret = "y".repeat(32)))
    assert(issues.none { "at least 32 characters" in it })
  }

  @Test
  fun `http monolith URL without allowInsecure is flagged`() {
    val issues = computeRuntimeConfigIssues(appConfig(monolithBaseUrl = "http://monolith.local"))
    assert(issues.any { "MONOLITH_BASE_URL must use https" in it })
  }

  @Test
  fun `http monolith URL with allowInsecure is accepted`() {
    val issues = computeRuntimeConfigIssues(
      appConfig(monolithBaseUrl = "http://monolith.local", allowInsecureMonolithUrl = true),
    )
    assert(issues.none { "MONOLITH_BASE_URL" in it })
  }

  @Test
  fun `https monolith URL is accepted`() {
    val issues = computeRuntimeConfigIssues(appConfig(monolithBaseUrl = "https://monolith.example.org"))
    assert(issues.none { "MONOLITH_BASE_URL" in it })
  }

  @Test
  fun `multiple issues accumulate`() {
    val issues = computeRuntimeConfigIssues(
      appConfig(
        shopify = shopify(appClientId = "your_id", publicBaseUrl = "http://example.com"),
        dssInternalSecret = "short",
      ),
    )
    assert(issues.size >= 3)
  }
}
