package dropnext.dss.testing.fake

import dropnext.dss.config.Config

fun testConfig(
  appClientSecret: String = "test-secret",
  apiVersion: String = "2026-04",
  monolithWebhookAuthSecret: String = "x".repeat(32),
  monolithBaseUrl: String = "https://monolith.test",
): Config =
  Config(
    appClientId = "client-id-test",
    appClientSecret = appClientSecret,
    scopes = "read_orders",
    dssBaseUrl = "https://dss.test",
    oauthRedirectPath = "/oauth/callback",
    apiVersion = apiVersion,
    serverPort = 8080,
    monolithBaseUrl = monolithBaseUrl,
    monolithApiPrefix = null,
    monolithApiKey = null,
    allowInsecureMonolithUrl = true,
    monolithWebhookAuthSecret = monolithWebhookAuthSecret,
  )

fun testShopifyConfig(
  appClientSecret: String = "test-secret",
  apiVersion: String = "2026-04",
): Config = testConfig(
  appClientSecret = appClientSecret,
  apiVersion = apiVersion,
)
