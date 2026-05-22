package dropnext.dss.testing.fake

import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.lib.dss.ShopAccessTokenCache

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

fun testDssAppConfig(
  shopify: ShopifyConfig = testShopifyConfig(),
  dssInternalSecret: String? = null,
  sandboxFakeShopify: Boolean = false,
  syncOrderOnUpdated: Boolean = false,
  monolithBaseUrl: String? = null,
): DssAppConfig =
  DssAppConfig(
    shopify = shopify,
    monolithBaseUrl = monolithBaseUrl,
    monolithApiPrefix = null,
    monolithApiKey = null,
    monolithCreateOrderPath = "/orders",
    dssInternalSecret = dssInternalSecret,
    enableDemoRoutes = false,
    enableTestHarness = false,
    sandboxFakeShopify = sandboxFakeShopify,
    allowInsecureMonolithUrl = true,
    syncOrderOnUpdated = syncOrderOnUpdated,
  )

fun testShopTokens(initial: Map<String, String> = emptyMap()): ShopAccessTokenCache =
  ShopAccessTokenCache(initial)
