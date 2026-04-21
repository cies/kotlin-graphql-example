package com.example

import com.example.dss.DssAppConfig
import com.example.dss.DssFulfillmentService
import com.example.dss.MonolithClient
import com.example.dss.persistence.FileStoreRepository
import com.example.shopify.OAuthStateStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
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
  val stateStore = OAuthStateStore()
  val storeRepo = FileStoreRepository(dssConfig.dataDir)
  val fulfillmentService = DssFulfillmentService()
  val httpClient = createSharedHttpClient()
  val monolithClient =
    dssConfig.monolithBaseUrl?.let { base ->
      MonolithClient(
        httpClient = httpClient,
        baseUrl = base,
        apiKey = dssConfig.monolithApiKey,
        createOrderPath = dssConfig.monolithCreateOrderPath,
      )
    }

  embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") {
    install(CallLogging)
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled error", cause)
        // Do not return exception messages to clients (may leak paths, SQL, or secrets).
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
      stateStore = stateStore,
      storeRepo = storeRepo,
      httpClient = httpClient,
      fulfillmentService = fulfillmentService,
      monolithClient = monolithClient,
    )
  }.start(wait = true)
}
