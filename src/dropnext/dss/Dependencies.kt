package dropnext.dss

import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.shopAccessTokensFromEnv
import dropnext.dss.handler.DemoHandlers
import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.handler.DssHttpHandlers
import dropnext.dss.handler.OAuthHandlers
import dropnext.dss.handler.WebhookHandlers
import dropnext.dss.lib.auth.ShopAccessTokenCache
import dropnext.dss.lib.fulfillment.DssFulfillmentService
import dropnext.dss.lib.monolith.HttpMonolithService
import dropnext.dss.lib.monolith.MonolithService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient


private val log = KotlinLogging.logger {}

/**
 * Every collaborator the running app needs, wired once. This is to be passsed to [dssModule].
 * Tests assemble the same shape (with fakes substituted) so the Ktor module mounted
 * in production runs unchanged under `testApplication`.
 */
data class DssDependencies(
  val config: DssAppConfig,
  val httpClient: HttpClient,
  val monolithHttpClient: HttpClient,
  val monolithService: MonolithService?,
  val gqlClientCache: GraphqlClientCache,
  val shopTokens: ShopAccessTokenCache,
  val diagnosticsHandlers: DiagnosticsHandlers,
  val oauthHandlers: OAuthHandlers,
  val webhookHandlers: WebhookHandlers,
  val demoHandlers: DemoHandlers,
  val dssHandlers: DssHttpHandlers,
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
fun dssDependencies(config: DssAppConfig): DssDependencies {
  val httpClient = createSharedHttpClient()
  val monolithHttpClient = createMonolithHttpClient(httpClient)
  val monolithService = monolithServiceFor(config, monolithHttpClient)
  val gqlClientCache = GraphqlClientCache(httpClient)
  val shopTokens = ShopAccessTokenCache(
    shopAccessTokensFromEnv(enableTestHarness = config.dev.enableTestHarness),
  )

  return DssDependencies(
    config = config,
    httpClient = httpClient,
    monolithHttpClient = monolithHttpClient,
    monolithService = monolithService,
    gqlClientCache = gqlClientCache,
    shopTokens = shopTokens,
    diagnosticsHandlers = DiagnosticsHandlers(config, shopTokens),
    oauthHandlers = OAuthHandlers(config, httpClient, gqlClientCache, monolithService, shopTokens),
    webhookHandlers = WebhookHandlers(config, gqlClientCache, monolithService, shopTokens),
    demoHandlers = DemoHandlers(config, gqlClientCache, monolithService, shopTokens),
    dssHandlers = DssHttpHandlers(
      shopifyConfig = config.shopify,
      dssConfig = config,
      gqlClientCache = gqlClientCache,
      fulfillmentService = DssFulfillmentService,
      monolithService = monolithService,
      shopTokens = shopTokens,
    ),
  )
}

private fun monolithServiceFor(config: DssAppConfig, client: HttpClient): MonolithService? {
  val monolithConfig = config.monolith
  return HttpMonolithService(
    httpClient = client,
    baseUrl = monolithConfig.baseUrl ?: return null,
    apiPathPrefix = monolithConfig.apiPrefix,
    apiKey = monolithConfig.apiKey,
    createOrderPath = monolithConfig.createOrderPath,
  )
}
