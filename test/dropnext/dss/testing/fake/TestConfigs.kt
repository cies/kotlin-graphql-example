package dropnext.dss.testing.fake

import dropnext.dss.config.DevConfig
import dropnext.dss.config.Config
import dropnext.dss.config.MonolithConfig
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.config.WebhookConfig
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.shopify.ShopDomain

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

fun testMonolithConfig(
  baseUrl: String = "https://monolith.test",
  apiPrefix: String? = null,
  apiKey: String? = null,
  createOrderPath: String = "/orders",
  allowInsecureUrl: Boolean = true,
): MonolithConfig =
  MonolithConfig(
    baseUrl = baseUrl,
    apiPrefix = apiPrefix,
    apiKey = apiKey,
    createOrderPath = createOrderPath,
    allowInsecureUrl = allowInsecureUrl,
  )

fun testDevConfig(
  enableDemoRoutes: Boolean = false,
  enableTestHarness: Boolean = false,
): DevConfig =
  DevConfig(
    enableDemoRoutes = enableDemoRoutes,
    enableTestHarness = enableTestHarness,
  )

fun testDssAppConfig(
  shopify: ShopifyConfig = testShopifyConfig(),
  dssInternalSecret: String? = null,
  syncOrderOnUpdated: Boolean = false,
  monolithBaseUrl: String = "https://monolith.test",
): Config =
  Config(
    shopify = shopify,
    monolith = testMonolithConfig(baseUrl = monolithBaseUrl),
    dev = testDevConfig(),
    webhook = WebhookConfig(syncOrderOnUpdated = syncOrderOnUpdated),
    monolithWebhookAuthSecret = dssInternalSecret,
  )

fun testShopTokens(initial: Map<ShopDomain, String> = emptyMap()): ShopAccessTokenCache =
  ShopAccessTokenCache(initial)
