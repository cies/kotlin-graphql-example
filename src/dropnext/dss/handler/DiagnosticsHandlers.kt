package dropnext.dss.handler

import dropnext.dss.config.Config
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.path.Paths
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable


/** Handlers for the diagnostic/health endpoints (`/`, `/health`, `/api`, `/api/check`, …). */
class DiagnosticsHandlers(
  private val dssConfig: Config,
  private val shopTokens: ShopTokenStore,
) {
  suspend fun handleIndex(call: ApplicationCall) {
    call.respondText(
      """
      API is running.

      --- Infrastructure / diagnostics ---
        GET  ${Paths.index.padEnd(26)}This index
        GET  ${Paths.health.padEnd(26)}Liveness probe - returns "ok"
        GET  ${Paths.api.padEnd(26)}JSON diagnostic info (config, URLs, issues)
        GET  ${Paths.apiCheck.padEnd(20)}?shop=  Readiness for a specific shop (token resolvable)
        GET  ${Paths.apiRedirectUrl.padEnd(26)}Full OAuth redirect URL

      --- Shopify OAuth ---
        GET  ${Paths.install.padEnd(20)}?shop=  Start OAuth - redirects to Shopify authorize URL
        GET  ${dssConfig.oauthRedirectPath.padEnd(26)}OAuth callback - code exchange, saves token, registers webhooks

      --- Webhooks ---
        POST ${Paths.webhooksShopify.padEnd(26)}Shopify webhook receiver (products/*, orders/*)

      --- DSS Internal API (Authorization: Bearer <DSS_API_KEY> required) ---
        PUT  ${Paths.storesApiKey.padEnd(40)} Set Shopify Admin token (see the checked-in monolith contract)
        POST ${Paths.syncShipmentsWithFulfillments.padEnd(40)} Monolith webhook: SyncShipmentsWithFulfillmentsRequest -> sync Shopify fulfillments
        POST ${Paths.trackingUpdate.padEnd(40)} Monolith webhook: TrackingUpdateRequest -> Shopify FulfillmentEvent
      """.trimIndent(),
      ContentType.Text.Plain,
      HttpStatusCode.OK,
    )
  }

  suspend fun handleApiStatus(call: ApplicationCall) {
    call.respond(
      ApiStatusResponse(
        status = "ok",
        bind = "0.0.0.0:${dssConfig.serverPort}",
        dssBaseUrl = dssConfig.dssBaseUrl,
        oauthRedirectPath = dssConfig.oauthRedirectPath,
      ),
    )
  }

  suspend fun handleApiCheck(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespond(rawShop, "shop") ?: return

    // Goes through the store rather than the cache alone, so the check answers what a webhook would find.
    if (shopTokens.resolve(shop) == null) {
      return call.respondError(DssError.MissingShopifyAdminToken)
    }

    call.respond(
      ApiCheckResponse(
        shop = shop.normalizedShopifyHost,
        checks = ApiCheckDetails(hasTokenMappedForShop = true),
      ),
    )
  }

  suspend fun handleHealth(call: ApplicationCall) {
    call.respondText("ok")
  }

  suspend fun handleRedirectUrl(call: ApplicationCall) {
    call.respondText(dssConfig.redirectUrl, ContentType.Text.Plain, HttpStatusCode.OK)
  }
}


@Serializable
private data class ApiStatusResponse(
  val status: String,
  val bind: String,
  val dssBaseUrl: String,
  val oauthRedirectPath: String,
)

@Serializable
private data class ApiCheckResponse(
  val shop: String,
  val checks: ApiCheckDetails,
)

@Serializable
private data class ApiCheckDetails(
  val hasTokenMappedForShop: Boolean,
)
