package dropnext.dss

import dropnext.dss.config.Config
import dropnext.dss.lib.ktor.plugin.installMonolithWebhookAuth
import dropnext.dss.lib.ktor.installStatusPages
import dropnext.dss.lib.ktor.installTraceId
import dropnext.dss.lib.ktor.installJsonContentNegotiation
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
  val server = embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") {
    installTraceId()
    installStatusPages()
    installJsonContentNegotiation()
    installMonolithWebhookAuth(deps.config.monolithWebhookAuthSecret)

    routing {
      installDiagnosticsRoutes(handlers = deps.diagnosticsHandlers)
      installOAuthRoutes(
        handlers = deps.oauthHandlers,
        oauthCallbackPath = deps.config.oauthRedirectPath,
      )
      installShopifyWebhookRoutes(handlers = deps.shopifyWebhookHandlers)
      installMonolithWebhookRoutes(handlers = deps.monolithWebhookHandlers)
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread {
    log.info { "[shutdown] SIGTERM received - stopping HTTP server with 3s grace, 10s timeout" }
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
  log.info {
    "[http] Listening on 0.0.0.0:${config.serverPort} with DSS_BASE_URL=${config.dssBaseUrl} - " +
      "reverse-proxy target port must equal ${config.serverPort} (unset PORT locally -> 8080; empty PORT in Docker -> 9999)."
  }
  val prefixNote = config.monolithApiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()
  val bearerConfigured = !config.monolithApiKey.isNullOrBlank()
  log.info {
    "[monolith] Outbound enabled: MONOLITH_BASE_URL=${config.monolithBaseUrl}$prefixNote " +
      "(MONOLITH_API_KEY Bearer configured: $bearerConfigured)."
  }
}
