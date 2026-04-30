package shopify.service.app

import com.example.lib.dss.DssAppConfig
import com.example.lib.dss.DssFulfillmentService
import com.example.lib.dss.DssHttpHandlers
import com.example.lib.monolith.HttpMonolithClient
import com.example.lib.monolith.MonolithCreateOrderPort
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json

fun main() {
  val dssConfig =
    DssAppConfig.fromEnv()
      ?: error(
        "Set env vars: SHOPIFY_API_KEY, SHOPIFY_API_SECRET, SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
  val config = dssConfig.shopify
  if (dssConfig.enableTestHarness) {
    println(
      "[test-harness] /demo/* routes are enabled (same as ENABLE_DEMO_ROUTES=true for this process)",
    )
    println(
      "[test-harness] Stateless tokens: set DSS_SHOP_ACCESS_TOKENS or X-Shopify-Access-Token on DSS calls; SANDBOX_* merged when harness is on.",
    )
  }
  val httpClient = createSharedHttpClient()
  val httpMonolithClient: MonolithCreateOrderPort? =
    dssConfig.monolithBaseUrl?.let { base ->
      HttpMonolithClient(
        httpClient = httpClient,
        baseUrl = base,
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
    )

  embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") {
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        System.err.println("Unhandled error: ${cause.message}")
        cause.printStackTrace()
        call.respondText(
          text = "internal error",
          status = io.ktor.http.HttpStatusCode.InternalServerError,
        )
      }
    }
    install(ContentNegotiation) {
      json(
        Json {
          ignoreUnknownKeys = true
          isLenient = true
        },
      )
    }
    configureRouting(
      dssConfig = dssConfig,
      httpClient = httpClient,
      httpMonolithClient = httpMonolithClient,
      dssHandlers = dssHandlers,
    )
  }.start(wait = true)
}
