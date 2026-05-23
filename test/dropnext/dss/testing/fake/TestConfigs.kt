package dropnext.dss.testing.fake

import dropnext.dss.config.DevConfig
import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.MonolithConfig
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.config.WebhookConfig
import dropnext.dss.lib.auth.ShopAccessTokenCache

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
  baseUrl: String? = null,
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
  sandboxFakeShopify: Boolean = false,
): DevConfig =
  DevConfig(
    enableDemoRoutes = enableDemoRoutes,
    enableTestHarness = enableTestHarness,
    sandboxFakeShopify = sandboxFakeShopify,
  )

fun testDssAppConfig(
  shopify: ShopifyConfig = testShopifyConfig(),
  dssInternalSecret: String? = null,
  sandboxFakeShopify: Boolean = false,
  syncOrderOnUpdated: Boolean = false,
  monolithBaseUrl: String? = null,
): DssAppConfig =
  DssAppConfig(
    shopify = shopify,
    monolith = testMonolithConfig(baseUrl = monolithBaseUrl),
    dev = testDevConfig(sandboxFakeShopify = sandboxFakeShopify),
    webhook = WebhookConfig(syncOrderOnUpdated = syncOrderOnUpdated),
    dssInternalSecret = dssInternalSecret,
  )

fun testShopTokens(initial: Map<String, String> = emptyMap()): ShopAccessTokenCache =
  ShopAccessTokenCache(initial)
