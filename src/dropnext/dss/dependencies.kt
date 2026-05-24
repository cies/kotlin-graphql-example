package dropnext.dss

import dropnext.dss.config.Config
import dropnext.dss.config.shopAccessTokensFromEnv
import dropnext.dss.handler.DemoHandlers
import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.handler.MonolithWebhookHandlers
import dropnext.dss.handler.OAuthHandlers
import dropnext.dss.handler.ShopifyWebhookHandlers
import dropnext.dss.lib.ktor.createMonolithHttpClient
import dropnext.dss.lib.ktor.createSharedHttpClient
import dropnext.dss.lib.monolith.HttpMonolithService
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyServiceFactory
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient


private val log = KotlinLogging.logger {}

/**
 * Every collaborator the running app needs, wired once. This is passed to [dssModule].
 * Tests assemble the same shape (with fakes substituted via the optional [dssDependencies]
 * parameters) so the Ktor module mounted in production runs unchanged under `testApplication`.
 */
data class DssDependencies(
  val config: Config,
  val httpClient: HttpClient,
  val monolithHttpClient: HttpClient,
  val monolithService: MonolithService,
  val diagnosticsHandlers: DiagnosticsHandlers,
  val oauthHandlers: OAuthHandlers,
  val shopifyWebhookHandlers: ShopifyWebhookHandlers,
  val demoHandlers: DemoHandlers,
  val dssHandlers: MonolithWebhookHandlers,
) {
  /** Closes both HTTP clients with [runCatching] so a single failure doesn't skip the others. */
  fun close() {
    runCatching { httpClient.close() }
      .onFailure { log.warn(it) { "[shutdown] httpClient.close threw" } }
    runCatching { monolithHttpClient.close() }
      .onFailure { log.warn(it) { "[shutdown] monolithHttpClient.close threw" } }
  }
}

/**
 * Builds the [DssDependencies] graph from [config]. Every collaborator has a sensible
 * production default; tests swap in fakes by overriding the corresponding parameter. Defaults
 * are evaluated lazily and can reference earlier parameters, so overriding [httpClient] (for
 * example, the rewriting client that pins Shopify calls to a fake server) flows through to the
 * [shopifyServiceFactory] and [oauthClient] defaults automatically.
 *
 * Example test setup:
 * ```
 * val deps = dssDependencies(
 *   testConfig,
 *   httpClient = rewritingClient,
 *   monolithService = FakeMonolithService(),
 *   shopTokens = ShopAccessTokenCache(mapOf(shop to "shpat_test")),
 * )
 * val handler = deps.shopifyWebhookHandlers
 * ```
 */
fun dssDependencies(
  config: Config,
  httpClient: HttpClient = createSharedHttpClient(),
  monolithHttpClient: HttpClient = createMonolithHttpClient(httpClient),
  shopTokens: ShopAccessTokenCache =
    ShopAccessTokenCache(shopAccessTokensFromEnv(enableTestHarness = config.dev.enableTestHarness)),
  monolithService: MonolithService = HttpMonolithService(
    httpClient = monolithHttpClient,
    baseUrl = config.monolith.baseUrl,
    apiPathPrefix = config.monolith.apiPrefix,
    apiKey = config.monolith.apiKey,
    createOrderPath = config.monolith.createOrderPath,
  ),
  shopifyServiceFactory: ShopifyServiceFactory = ShopifyServiceFactory(
    httpClient = httpClient,
    tokens = shopTokens,
    monolith = monolithService,
    apiVersion = config.shopify.apiVersion,
  ),
  oauthClient: ShopifyOAuthService = ShopifyOAuthService(httpClient, config.shopify),
): DssDependencies = DssDependencies(
  config = config,
  httpClient = httpClient,
  monolithHttpClient = monolithHttpClient,
  monolithService = monolithService,
  diagnosticsHandlers = DiagnosticsHandlers(config, shopTokens),
  oauthHandlers = OAuthHandlers(
    config,
    oauthClient,
    shopifyServiceFactory,
    monolithService,
    shopTokens
  ),
  shopifyWebhookHandlers = ShopifyWebhookHandlers(config, shopifyServiceFactory, monolithService),
  demoHandlers = DemoHandlers(config, shopifyServiceFactory),
  dssHandlers = MonolithWebhookHandlers(shopifyServiceFactory, monolithService, shopTokens),
)
