package dropnext.dss

import dropnext.dss.config.DssAppConfig
import dropnext.dss.lib.ktor.installDssStatusPages
import dropnext.dss.lib.ktor.installDssTraceId
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installDemoRoutes
import dropnext.dss.routing.installDiagnosticsRoutes
import dropnext.dss.routing.installDssRoutes
import dropnext.dss.routing.installOAuthRoutes
import dropnext.dss.routing.installWebhookRoutes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing


private val log = KotlinLogging.logger {}

fun main() {
  val config = DssAppConfig.fromEnv()
  logStartupSummary(config)

  val deps = dssDependencies(config)
  val server = embeddedServer(CIO, port = config.shopify.serverPort, host = "0.0.0.0") {
    dssModule(deps)
  }

  Runtime.getRuntime().addShutdownHook(Thread {
    log.info { "[shutdown] SIGTERM received — stopping HTTP server with 3s grace, 10s timeout" }
    runCatching { server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000) }
      .onFailure { log.warn(it) { "[shutdown] server.stop threw" } }
    deps.close()
    log.info { "[shutdown] complete" }
  })

  server.start(wait = true)
}

/**
 * The Ktor module — installs cross-cutting plugins then wires the route trees onto the [deps] graph.
 * Lives outside `main()` so production and test (`testApplication`) share one routing path.
 */
fun Application.dssModule(deps: DssDependencies) {
  installDssTraceId()
  installDssStatusPages()
  installJsonContentNegotiation()
  routing {
    installDiagnosticsRoutes(handlers = deps.diagnosticsHandlers)
    installOAuthRoutes(
      handlers = deps.oauthHandlers,
      oauthCallbackPath = deps.config.shopify.oauthRedirectPath,
    )
    installWebhookRoutes(handlers = deps.webhookHandlers)
    if (deps.config.dev.enableDemoRoutes) installDemoRoutes(handlers = deps.demoHandlers)
    installDssRoutes(handlers = deps.dssHandlers)
  }
}

/**
 * Emits a compact summary of the effective runtime configuration at startup so operators can
 * verify env, container, and reverse-proxy expectations without diving into the code.
 */
private fun logStartupSummary(config: DssAppConfig) {
  val shopify = config.shopify
  log.info {
    "[http] Listening on 0.0.0.0:${shopify.serverPort}; PUBLIC_BASE_URL=${shopify.publicBaseUrl} — " +
      "reverse-proxy target port must equal ${shopify.serverPort} (unset PORT locally → 8080; empty PORT in Docker → 9999)."
  }
  if (config.dev.enableTestHarness) {
    log.info {
      "[test-harness] /demo/ routes forced on; SANDBOX_SHOP/SANDBOX_ACCESS_TOKEN merged into the token map " +
        "(set DSS_SHOP_ACCESS_TOKENS or pass X-Shopify-Access-Token for real shops)."
    }
  } else if (config.dev.enableDemoRoutes) {
    log.info { "[demo] /demo/ routes enabled via ENABLE_DEMO_ROUTES=true — disable in production." }
  }
  val base = config.monolith.baseUrl
  if (base.isNullOrBlank()) {
    log.info {
      "[monolith] MONOLITH_BASE_URL is unset — OAuth will not PUT the Shopify Admin token to the DropNext backend. " +
        "Configure MONOLITH_BASE_URL in prod and dev if installs should persist to the monolith."
    }
  } else {
    val prefixNote = config.monolith.apiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()
    val bearerConfigured = !config.monolith.apiKey.isNullOrBlank()
    log.info {
      "[monolith] Outbound enabled: MONOLITH_BASE_URL=$base$prefixNote " +
        "(MONOLITH_API_KEY Bearer configured: $bearerConfigured)."
    }
  }
}
