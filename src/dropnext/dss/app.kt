package dropnext.dss

import dropnext.dss.config.Config
import dropnext.dss.lib.ktor.plugin.installMonolithWebhookAuth
import dropnext.dss.lib.ktor.installStatusPages
import dropnext.dss.lib.ktor.installTraceId
import dropnext.dss.lib.ktor.installJsonContentNegotiation
import dropnext.dss.routing.installDemoRoutes
import dropnext.dss.routing.installDiagnosticsRoutes
import dropnext.dss.routing.installMonolithWebhookRoutes
import dropnext.dss.routing.installOAuthRoutes
import dropnext.dss.routing.installShopifyWebhookRoutes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing


private val log = KotlinLogging.logger {}

fun main() {
  val config = Config.fromEnv()
  logConfigSummary(config)

  val deps = dssDependencies(config)
  val server = embeddedServer(CIO, port = config.shopify.serverPort, host = "0.0.0.0") {
    installTraceId()
    installStatusPages()
    installJsonContentNegotiation()
    installMonolithWebhookAuth(deps.config.monolithWebhookAuthSecret)

    routing {
      installDiagnosticsRoutes(handlers = deps.diagnosticsHandlers)
      installOAuthRoutes(
        handlers = deps.oauthHandlers,
        oauthCallbackPath = deps.config.shopify.oauthRedirectPath,
      )
      installShopifyWebhookRoutes(handlers = deps.shopifyWebhookHandlers)
      if (deps.config.dev.enableDemoRoutes) installDemoRoutes(handlers = deps.demoHandlers)
      installMonolithWebhookRoutes(handlers = deps.monolithWebhookHandlers)
    }
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
 * Emits a compact summary of the effective runtime configuration at startup so operators can
 * verify env, container, and reverse-proxy expectations without diving into the code.
 */
private fun logConfigSummary(config: Config) {
  val shopify = config.shopify
  log.info {
    "[http] Listening on 0.0.0.0:${shopify.serverPort}; PUBLIC_BASE_URL=${shopify.publicBaseUrl} — " +
      "reverse-proxy target port must equal ${shopify.serverPort} (unset PORT locally → 8080; empty PORT in Docker → 9999)."
  }
  if (config.dev.enableTestHarness) {
    log.info {
      "[test-harness] /demo/ routes forced on; SANDBOX_SHOP/SANDBOX_ACCESS_TOKEN merged into the token map " +
        "(configure DSS_SHOP_ACCESS_TOKENS for real shops, or complete the OAuth install flow)."
    }
  } else if (config.dev.enableDemoRoutes) {
    log.info { "[demo] /demo/ routes enabled via ENABLE_DEMO_ROUTES=true — disable in production." }
  }
  val prefixNote = config.monolith.apiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()
  val bearerConfigured = !config.monolith.apiKey.isNullOrBlank()
  log.info {
    "[monolith] Outbound enabled: MONOLITH_BASE_URL=${config.monolith.baseUrl}$prefixNote " +
      "(MONOLITH_API_KEY Bearer configured: $bearerConfigured)."
  }
}
