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
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentService
import dropnext.dss.lib.shopify.graphql.fulfillment.SandboxFulfillmentService
import dropnext.dss.lib.shopify.graphql.fulfillment.ShopifyFulfillmentService
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient


private val log = KotlinLogging.logger {}

/**
 * Every collaborator the running app needs, wired once. This is passed to [dssModule].
 * Tests assemble the same shape (with fakes substituted) so the Ktor module mounted in
 * production runs unchanged under `testApplication`.
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

/** Builds the production [DssDependencies] graph from [config]. */
fun dssDependencies(config: Config): DssDependencies {
  val httpClient = createSharedHttpClient()
  val monolithHttpClient = createMonolithHttpClient(httpClient)
  val monolithService: MonolithService = HttpMonolithService(
    httpClient = monolithHttpClient,
    baseUrl = config.monolith.baseUrl,
    apiPathPrefix = config.monolith.apiPrefix,
    apiKey = config.monolith.apiKey,
    createOrderPath = config.monolith.createOrderPath,
  )
  val shopTokens = ShopAccessTokenCache(
    shopAccessTokensFromEnv(enableTestHarness = config.dev.enableTestHarness),
  )
  val shopifyServiceFactory = ShopifyServiceFactory(
    httpClient = httpClient,
    tokens = shopTokens,
    monolith = monolithService,
    apiVersion = config.shopify.apiVersion,
  )
  val oauthClient = ShopifyOAuthClient(httpClient, config.shopify)
  val fulfillmentService: FulfillmentService = if (config.dev.sandboxFakeShopify) {
    SandboxFulfillmentService()
  } else {
    ShopifyFulfillmentService(shopifyServiceFactory)
  }

  return DssDependencies(
    config = config,
    httpClient = httpClient,
    monolithHttpClient = monolithHttpClient,
    monolithService = monolithService,
    diagnosticsHandlers = DiagnosticsHandlers(config, shopTokens),
    oauthHandlers = OAuthHandlers(config, oauthClient, shopifyServiceFactory, monolithService, shopTokens),
    shopifyWebhookHandlers = ShopifyWebhookHandlers(config, shopifyServiceFactory, monolithService),
    demoHandlers = DemoHandlers(config, shopifyServiceFactory),
    dssHandlers = MonolithWebhookHandlers(fulfillmentService, monolithService, shopTokens),
  )
}
