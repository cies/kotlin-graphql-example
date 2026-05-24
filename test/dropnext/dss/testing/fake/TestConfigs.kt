package dropnext.dss.testing.fake

import dropnext.dss.config.DevConfig
import dropnext.dss.config.Config
import dropnext.dss.config.MonolithConfig
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.config.WebhookConfig
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyServiceFactory
import dropnext.dss.lib.shopify.ShopDomain
import io.ktor.client.HttpClient

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
  monolithBaseUrl: String = "https://monolith.test",
): Config =
  Config(
    shopify = shopify,
    monolith = testMonolithConfig(baseUrl = monolithBaseUrl),
    dev = testDevConfig(sandboxFakeShopify = sandboxFakeShopify),
    webhook = WebhookConfig(syncOrderOnUpdated = syncOrderOnUpdated),
    dssInternalSecret = dssInternalSecret,
  )

fun testShopTokens(initial: Map<ShopDomain, String> = emptyMap()): ShopAccessTokenCache =
  ShopAccessTokenCache(initial)

/**
 * Single test-side construction point for [ShopifyServiceFactory]. Tests should not reach into
 * the factory's private collaborators (the internal `GraphqlClientCache`, etc.) — when the
 * factory's ctor shape changes, only this helper needs an update.
 */
fun testShopifyServiceFactory(
  httpClient: HttpClient,
  monolith: MonolithService = FakeMonolithService(),
  tokens: ShopAccessTokenCache = ShopAccessTokenCache(),
  apiVersion: String = "2026-04",
): ShopifyServiceFactory =
  ShopifyServiceFactory(
    httpClient = httpClient,
    tokens = tokens,
    monolith = monolith,
    apiVersion = apiVersion,
  )
