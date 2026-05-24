package dropnext.dss.testing.fake

import dropnext.dss.config.DevConfig
import dropnext.dss.config.Config
import dropnext.dss.config.MonolithConfig
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.config.WebhookConfig

fun testShopifyConfig(
  appClientSecret: String = "test-secret",
  apiVersion: String = "2026-04",
): ShopifyConfig =
  ShopifyConfig(
    appClientId = "client-id-test",
    appClientSecret = appClientSecret,
    scopes = "read_orders",
    publicBaseUrl = "https://dss.test",
    oauthRedirectPath = "/oauth/callback",
    apiVersion = apiVersion,
    serverPort = 0,
  )

fun testConfig(
  shopify: ShopifyConfig = testShopifyConfig(),
  monolithWebhookAuthSecret: String? = null,
  syncOrderOnUpdated: Boolean = false,
  monolithBaseUrl: String = "https://monolith.test",
): Config =
  Config(
    shopify = shopify,
    monolith = MonolithConfig(
      baseUrl = monolithBaseUrl,
      apiPrefix = null,
      apiKey = null,
      allowInsecureUrl = true,
    ),
    dev = DevConfig(enableDemoRoutes = false, enableTestHarness = false),
    webhook = WebhookConfig(syncOrderOnUpdated = syncOrderOnUpdated),
    monolithWebhookAuthSecret = monolithWebhookAuthSecret,
  )
