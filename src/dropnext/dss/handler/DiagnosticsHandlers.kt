package dropnext.dss.handler

import dropnext.dss.config.Config
import dropnext.dss.path.Paths
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.shopify.ShopDomain
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable


/**
 * Handlers for the diagnostic/health endpoints (`/`, `/health`, `/api`, `/api/check`, …).
 */
class DiagnosticsHandlers(
  private val dssConfig: Config,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val shopifyConfig = dssConfig.shopify

  suspend fun handleIndex(call: ApplicationCall) {
    val demoNote =
      if (dssConfig.dev.enableDemoRoutes) " (enabled)" else " (disabled — set ENABLE_DEMO_ROUTES=true)"
    call.respondText(
      """
      API is running.

      --- Infrastructure / diagnostics ---
        GET  ${Paths.index.padEnd(26)}This index
        GET  ${Paths.health.padEnd(26)}Liveness probe — returns "ok"
        GET  ${Paths.api.padEnd(26)}JSON diagnostic info (config, URLs, issues)
        GET  ${Paths.apiCheck.padEnd(20)}?shop=  Readiness for a specific shop (token + feature flags)
        GET  ${Paths.apiRedirectUrl.padEnd(26)}Full OAuth redirect URL

      --- Shopify OAuth ---
        GET  ${Paths.install.padEnd(20)}?shop=  Start OAuth — redirects to Shopify authorize URL
        GET  ${shopifyConfig.oauthRedirectPath.padEnd(26)}OAuth callback — code exchange, saves token, registers webhooks

      --- Webhooks ---
        POST ${Paths.webhooksShopify.padEnd(26)}Shopify webhook receiver (products/*, orders/*)

      --- DSS Internal API (X-DSS-Internal-Secret header required if configured) ---
        PUT  ${Paths.storesApiKey.padEnd(40)} Set Shopify Admin token (see repo openapi.json)
        POST ${Paths.syncShipmentsWithFulfillments.padEnd(40)} Monolith webhook: SyncShipmentsWithFulfillmentsRequest → sync Shopify fulfillments
        POST ${Paths.trackingUpdate.padEnd(40)} Monolith webhook: TrackingUpdateRequest → Shopify FulfillmentEvent
        POST ${Paths.trackingUpdates.padEnd(40)} Alias for ${Paths.trackingUpdate} (same TrackingUpdateRequest body)

      --- Demo routes$demoNote ---
        GET  ${Paths.demoProducts.padEnd(28)}?shop=     List products via Shopify Graphql
        GET  ${Paths.demoOrder.padEnd(28)}?shop=&id=  Load a single order by GID or numeric ID
        POST ${Paths.demoFulfillmentCreate.padEnd(40)} Create a fulfillment with tracking
        POST ${Paths.demoFulfillmentTracking.padEnd(40)} Update fulfillment tracking info
      """.trimIndent(),
      ContentType.Text.Plain,
      HttpStatusCode.OK,
    )
  }

  suspend fun handleApiStatus(call: ApplicationCall) {
    call.respond(
      ApiStatusResponse(
        status = "ok",
        bind = "0.0.0.0:${shopifyConfig.serverPort}",
        dssBaseUrl = shopifyConfig.dssBaseUrl,
        oauthRedirectPath = shopifyConfig.oauthRedirectPath,
      ),
    )
  }

  suspend fun handleApiCheck(call: ApplicationCall) {
    val rawShop = call.request.queryParameters["shop"]
      ?: return call.respondError(DssError.MissingParameter("shop"))

    val shop = ShopDomain.parse(rawShop)
      ?: return call.respondError(DssError.InvalidParameter("shop", "not a valid Shopify domain"))

    val hasMappedToken = shopTokens[shop]?.isNotBlank() == true
    if (!hasMappedToken) {
      return call.respondError(DssError.MissingShopifyAdminToken)
    }

    call.respond(
      ApiCheckResponse(
        shop = shop.normalizedShopifyHost,
        checks = ApiCheckDetails(
          hasTokenMappedForShop = hasMappedToken,
          demoRoutesEnabled = dssConfig.dev.enableDemoRoutes,
          testHarnessEnabled = dssConfig.dev.enableTestHarness,
        ),
      ),
    )
  }

  // REMOVED: this is something that logging should do (and the app should not start!)
  // suspend fun handleApiReady(call: ApplicationCall) { ... }

  suspend fun handleHealth(call: ApplicationCall) {
    call.respondText("ok")
  }

  suspend fun handleRedirectUrl(call: ApplicationCall) {
    call.respondText(shopifyConfig.redirectUrl, ContentType.Text.Plain, HttpStatusCode.OK)
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
  val shop: String?,
  val checks: ApiCheckDetails,
)

@Serializable
private data class ApiCheckDetails(
  val hasTokenMappedForShop: Boolean,
  val demoRoutesEnabled: Boolean,
  val testHarnessEnabled: Boolean,
)
