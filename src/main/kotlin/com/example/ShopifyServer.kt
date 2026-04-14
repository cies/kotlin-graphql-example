package com.example

import com.example.config.ShopifyConfig
import com.example.shopify.AccessTokenStore
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
  val config =
    ShopifyConfig.fromEnv()
      ?: error(
        "Set env vars: SHOPIFY_API_KEY, SHOPIFY_API_SECRET, SHOPIFY_SCOPES, PUBLIC_BASE_URL",
      )
  val stateStore = OAuthStateStore()
  val tokenStore = AccessTokenStore()
  val httpClient = createSharedHttpClient()

  embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") {
    install(CallLogging)
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled error", cause)
        val msg = cause.message?.takeIf { it.isNotBlank() } ?: "internal error"
        call.respondText(text = msg, status = io.ktor.http.HttpStatusCode.InternalServerError)
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
    configureRouting(config, stateStore, tokenStore, httpClient)
  }.start(wait = true)
}
