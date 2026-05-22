package dropnext.dss

import dropnext.dss.config.DssAppConfig
import dropnext.dss.handler.DemoHandlers
import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.handler.DssHttpHandlers
import dropnext.dss.handler.OAuthHandlers
import dropnext.dss.handler.WebhookHandlers
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
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*


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
  val httpMonolithClient: MonolithService? = dssConfig.monolithBaseUrl?.let { base ->
    HttpMonolithService(
      httpClient = httpClient,
      baseUrl = base,
      apiPathPrefix = dssConfig.monolithApiPrefix,
      apiKey = dssConfig.monolithApiKey,
      createOrderPath = dssConfig.monolithCreateOrderPath,
    )
  }

  val gqlClientCache = GraphQLClientCache(httpClient)

  val diagnosticsHandlers = DiagnosticsHandlers(dssConfig)
  val oauthHandlers = OAuthHandlers(dssConfig, httpClient, gqlClientCache, httpMonolithClient)
  val webhookHandlers = WebhookHandlers(dssConfig, gqlClientCache, httpMonolithClient)
  val demoHandlers = DemoHandlers(dssConfig, gqlClientCache, httpMonolithClient)
  val dssHandlers = DssHttpHandlers(
    shopifyConfig = shopifyConfig,
    dssConfig = dssConfig,
    gqlClientCache = gqlClientCache, // CHECK(cies): Is it okay to use the cache here as well?
    fulfillmentService = DssFulfillmentService(),
    monolithService = httpMonolithClient,
  )

  embeddedServer(CIO, port = shopifyConfig.serverPort, host = "0.0.0.0") {
    installDssTraceId()
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        System.err.println("Unhandled error: ${cause.message}")
        cause.printStackTrace()
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
  }.start(wait = true)
}
