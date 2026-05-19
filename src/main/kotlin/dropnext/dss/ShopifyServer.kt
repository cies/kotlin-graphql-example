package dropnext.dss

import dropnext.dss.lib.dss.DssAppConfig
import dropnext.dss.lib.dss.DssFulfillmentService
import dropnext.dss.lib.dss.DssHttpHandlers
import dropnext.dss.lib.monolith.HttpMonolithService
import dropnext.dss.lib.monolith.MonolithService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText

fun main() {
  val dssConfig =
    DssAppConfig.fromEnv()
      ?: error(
        "Set env vars: SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY), SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET), SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
  val config = dssConfig.shopify

  println(
    "[http] Listening on 0.0.0.0:${config.serverPort}; PUBLIC_BASE_URL=${config.publicBaseUrl} — " +
      "reverse-proxy target port must equal ${config.serverPort} (unset PORT locally → 8080; empty PORT in Docker → 9999).",
  )

  if (dssConfig.enableTestHarness) {
    println(
      "[test-harness] /demo/* routes are enabled (same as ENABLE_DEMO_ROUTES=true for this process)",
    )
    println(
      "[test-harness] Stateless tokens: set DSS_SHOP_ACCESS_TOKENS or X-Shopify-Access-Token on DSS calls; SANDBOX_* merged when harness is on.",
    )
  }
  if (dssConfig.monolithBaseUrl.isNullOrBlank()) {
    println(
      "[monolith] MONOLITH_BASE_URL is unset — OAuth will not PUT the Shopify Admin token to the DropNext backend. " +
        "Configure MONOLITH_BASE_URL in prod and dev if installs should persist to the monolith.",
    )
  } else {
    val bearerConfigured = !dssConfig.monolithApiKey.isNullOrBlank()
    println(
      "[monolith] Outbound enabled: MONOLITH_BASE_URL=${dssConfig.monolithBaseUrl}${dssConfig.monolithApiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()} " +
        "(MONOLITH_API_KEY Bearer configured: $bearerConfigured).",
    )
  }
  val httpClient = createSharedHttpClient()
  val httpMonolithClient: MonolithService? =
    dssConfig.monolithBaseUrl?.let { base ->
      HttpMonolithService(
        httpClient = httpClient,
        baseUrl = base,
        apiPathPrefix = dssConfig.monolithApiPrefix,
        apiKey = dssConfig.monolithApiKey,
        createOrderPath = dssConfig.monolithCreateOrderPath,
      )
    }
  val dssHandlers =
    DssHttpHandlers(
      shopifyConfig = config,
      dssConfig = dssConfig,
      httpClient = httpClient,
      fulfillmentService = DssFulfillmentService(),
      monolithService = httpMonolithClient,
    )
  val gqlClientCache = GraphQLClientCache(httpClient)

  embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") {
    installDssTraceId()
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        System.err.println("Unhandled error: ${cause.message}")
        cause.printStackTrace()
        call.respondText(
          text = "internal error",
          status = HttpStatusCode.InternalServerError,
        )
      }
    }
    install(ContentNegotiation) {
      json(AppJson)
    }
    configureRouting(
      dssConfig = dssConfig,
      httpClient = httpClient,
      httpMonolithClient = httpMonolithClient,
      dssHandlers = dssHandlers,
      gqlClientCache = gqlClientCache,
    )
  }.start(wait = true)
}
