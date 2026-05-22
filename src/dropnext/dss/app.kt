package dropnext.dss

import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.shopAccessTokensFromEnv
import dropnext.dss.handler.DemoHandlers
import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.handler.DssHttpHandlers
import dropnext.dss.handler.OAuthHandlers
import dropnext.dss.handler.WebhookHandlers
import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.fulfillment.DssFulfillmentService
import dropnext.dss.lib.json.AppJson
import dropnext.dss.lib.ktor.installDssTraceId
import dropnext.dss.lib.ktor.respondErrorText
import dropnext.dss.lib.monolith.HttpMonolithService
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.routing.installDemoRoutes
import dropnext.dss.routing.installDiagnosticsRoutes
import dropnext.dss.routing.installDssRoutes
import dropnext.dss.routing.installOAuthRoutes
import dropnext.dss.routing.installWebhookRoutes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing


private val log = KotlinLogging.logger {}

fun main() {
  val dssConfig = DssAppConfig.fromEnv() ?: return
  val shopifyConfig = dssConfig.shopify

  log.info {
    "[http] Listening on 0.0.0.0:${shopifyConfig.serverPort}; PUBLIC_BASE_URL=${shopifyConfig.publicBaseUrl} — " +
      "reverse-proxy target port must equal ${shopifyConfig.serverPort} (unset PORT locally → 8080; empty PORT in Docker → 9999)."
  }

  if (dssConfig.enableTestHarness) {
    log.info {
      "[test-harness] /demo/* routes are enabled (same as ENABLE_DEMO_ROUTES=true for this process)\n" +
        "[test-harness] Stateless tokens: set DSS_SHOP_ACCESS_TOKENS or X-Shopify-Access-Token on DSS calls; SANDBOX_* merged when harness is on."
    }
  }
  if (dssConfig.monolithBaseUrl.isNullOrBlank()) {
    log.info {
      "[monolith] MONOLITH_BASE_URL is unset — OAuth will not PUT the Shopify Admin token to the DropNext backend. " +
        "Configure MONOLITH_BASE_URL in prod and dev if installs should persist to the monolith."
    }
  } else {
    val bearerConfigured = !dssConfig.monolithApiKey.isNullOrBlank()
    val monolithBaseUrl = dssConfig.monolithBaseUrl +
      dssConfig.monolithApiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()
    log.info {
      "[monolith] Outbound enabled: MONOLITH_BASE_URL=$monolithBaseUrl " +
        "(MONOLITH_API_KEY Bearer configured: $bearerConfigured)."
    }
  }
  val httpClient = createSharedHttpClient()
  val monolithHttpClient = createMonolithHttpClient(httpClient)
  val httpMonolithClient: MonolithService? = dssConfig.monolithBaseUrl?.let { base ->
    HttpMonolithService(
      httpClient = monolithHttpClient,
      baseUrl = base,
      apiPathPrefix = dssConfig.monolithApiPrefix,
      apiKey = dssConfig.monolithApiKey,
      createOrderPath = dssConfig.monolithCreateOrderPath,
    )
  }

  val gqlClientCache = GraphQLClientCache(httpClient)
  val shopTokens = ShopAccessTokenCache(shopAccessTokensFromEnv(enableTestHarness = dssConfig.enableTestHarness))

  val diagnosticsHandlers = DiagnosticsHandlers(dssConfig, shopTokens)
  val oauthHandlers = OAuthHandlers(dssConfig, httpClient, gqlClientCache, httpMonolithClient, shopTokens)
  val webhookHandlers = WebhookHandlers(dssConfig, gqlClientCache, httpMonolithClient, shopTokens)
  val demoHandlers = DemoHandlers(dssConfig, gqlClientCache, httpMonolithClient, shopTokens)
  val dssHandlers = DssHttpHandlers(
    shopifyConfig = shopifyConfig,
    dssConfig = dssConfig,
    gqlClientCache = gqlClientCache, // CHECK(cies): Is it okay to use the cache here as well?
    fulfillmentService = DssFulfillmentService(),
    monolithService = httpMonolithClient,
    shopTokens = shopTokens,
  )

  val appLog = log // captured to avoid shadowing by io.ktor.server.application.Application.log inside the module
  val server = embeddedServer(CIO, port = shopifyConfig.serverPort, host = "0.0.0.0") {
    installDssTraceId()
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        appLog.error(cause) { "Unhandled error on ${call.request.local.method.value} ${call.request.local.uri}" }
        call.respondErrorText("internal error")
      }
    }
    install(ContentNegotiation) {
      json(AppJson)
    }
    routing {
      installDiagnosticsRoutes(handlers = diagnosticsHandlers)
      installOAuthRoutes(
        handlers = oauthHandlers,
        oauthCallbackPath = dssConfig.shopify.oauthRedirectPath
      )
      installWebhookRoutes(handlers = webhookHandlers)
      if (dssConfig.enableDemoRoutes) installDemoRoutes(handlers = demoHandlers)
      installDssRoutes(handlers = dssHandlers)
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread {
    appLog.info { "[shutdown] SIGTERM received — stopping HTTP server with 3s grace, 10s timeout" }
    runCatching { server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000) }
      .onFailure { appLog.warn(it) { "[shutdown] server.stop threw" } }
    runCatching { httpClient.close() }
      .onFailure { appLog.warn(it) { "[shutdown] httpClient.close threw" } }
    runCatching { monolithHttpClient.close() }
      .onFailure { appLog.warn(it) { "[shutdown] monolithHttpClient.close threw" } }
    appLog.info { "[shutdown] complete" }
  })

  server.start(wait = true)
}
