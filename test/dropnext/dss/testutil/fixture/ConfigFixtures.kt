package dropnext.dss.testutil.fixture

import dropnext.dss.config.Config
import dropnext.dss.config.DssMode
import dropnext.dss.domain.DssApiKey
import dropnext.dss.domain.MonolithApiKey
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyAppSecret


fun testConfig(
  appClientSecret: String = "test-secret",
  apiVersion: String = Config.DEFAULT_SHOPIFY_API_VERSION,
  dssApiKey: String = "x".repeat(32),
  monolithBaseUrl: String = "https://monolith.test",
  monolithApiKey: String? = null,
  shopAccessTokens: Map<ShopDomain, ShopifyAdminToken> = emptyMap(),
  mode: DssMode = DssMode.PROD,
): Config =
  Config(
    appClientId = "client-id-test",
    appClientSecret = ShopifyAppSecret(appClientSecret),
    scopes = "read_orders",
    dssBaseUrl = "https://dss.test",
    oauthRedirectPath = "/oauth/callback",
    apiVersion = apiVersion,
    serverPort = 8080,
    monolithBaseUrl = monolithBaseUrl,
    monolithApiPrefix = null,
    monolithApiKey = monolithApiKey?.let(::MonolithApiKey),
    allowInsecureMonolithUrl = true,
    dssApiKey = DssApiKey(dssApiKey),
    shopAccessTokens = shopAccessTokens,
    // Log shipping stays off in tests: an appender would try to reach Logflare from the flush thread.
    logflareSourceName = null,
    logflareApiKey = null,
    logflareEndpoint = null,
    mode = mode,
  )
