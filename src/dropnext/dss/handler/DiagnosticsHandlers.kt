package dropnext.dss.handler

import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.ktor.respondBadRequestText
import dropnext.dss.lib.ktor.shopifyAccessTokenFromHeader
import dropnext.dss.shopify.normalizeShopDomain
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
  private val dssConfig: DssAppConfig,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val shopifyConfig = dssConfig.shopify

  suspend fun handleIndex(call: ApplicationCall) {
    val demoNote =
      if (dssConfig.enableDemoRoutes) " (enabled)" else " (disabled — set ENABLE_DEMO_ROUTES=true)"
    call.respondText(
      """
      API is running.

      --- Infrastructure / diagnostics ---
        GET  ${DssPaths.INDEX.padEnd(26)}This index
        GET  ${DssPaths.HEALTH.padEnd(26)}Liveness probe — returns "ok"
        GET  ${DssPaths.API.padEnd(26)}JSON diagnostic info (config, URLs, issues)
        GET  ${DssPaths.API_CHECK.padEnd(20)}?shop=  Readiness for a specific shop (token + feature flags)
        GET  ${DssPaths.API_REDIRECT_URL.padEnd(26)}Full OAuth redirect URL

      --- Shopify OAuth ---
        GET  ${DssPaths.INSTALL.padEnd(20)}?shop=  Start OAuth — redirects to Shopify authorize URL
        GET  ${shopifyConfig.oauthRedirectPath.padEnd(26)}OAuth callback — code exchange, saves token, registers webhooks

      --- Webhooks ---
        POST ${DssPaths.WEBHOOKS_SHOPIFY.padEnd(26)}Shopify webhook receiver (products/*, orders/*)

      --- DSS Internal API (X-DSS-Internal-Secret header required if configured) ---
        PUT  ${DssPaths.STORES_API_KEY.padEnd(40)} Set Shopify Admin token (see repo openapi.json)
        POST ${DssPaths.TRACKING_UPDATE.padEnd(40)} Monolith webhook: SyncShipmentsWithFulfillmentsRequest → sync Shopify fulfillments
        POST ${DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS.padEnd(40)} Monolith webhook: TrackingUpdateRequest → Shopify FulfillmentEvent
        POST ${DssPaths.TRACKING_UPDATES.padEnd(40)} Same TrackingUpdate body as .../sync-shipments-with-fulfillments (compat)

      --- Demo routes$demoNote ---
        GET  ${DssPaths.DEMO_PRODUCTS.padEnd(28)}?shop=     List products via Shopify Graphql
        GET  ${DssPaths.DEMO_ORDER.padEnd(28)}?shop=&id=  Load a single order by GID or numeric ID
        POST ${DssPaths.DEMO_FULFILLMENT_CREATE.padEnd(40)} Create a fulfillment with tracking
        POST ${DssPaths.DEMO_FULFILLMENT_TRACKING.padEnd(40)} Update fulfillment tracking info
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
        publicBaseUrl = shopifyConfig.publicBaseUrl,
        oauthRedirectPath = shopifyConfig.oauthRedirectPath,
      ),
    )
  }

  suspend fun handleApiCheck(call: ApplicationCall) {
    val rawShop = call.request.queryParameters["shop"]
      ?: return call.respondBadRequestText("Missing query param: shop")

    val normalizedShop = normalizeShopDomain(rawShop)
      ?: return call.respondBadRequestText("Invalid shop domain format")

    val headerToken = call.request.shopifyAccessTokenFromHeader()
    val hasHeaderToken = !headerToken.isNullOrBlank()
    val hasMappedToken = shopTokens[normalizedShop]?.isNotBlank() == true
    if (!hasHeaderToken && !hasMappedToken) {
      return call.respondBadRequestText("Missing Admin token. Provide X-Shopify-Access-Token header or configure DSS_SHOP_ACCESS_TOKENS.")
    }

    call.respond(
      ApiCheckResponse(
        shop = normalizedShop,
        checks = ApiCheckDetails(
          hasHeaderToken = hasHeaderToken,
          hasTokenMappedForShop = hasMappedToken,
          demoRoutesEnabled = dssConfig.enableDemoRoutes,
          testHarnessEnabled = dssConfig.enableTestHarness,
          monolithConfigured = !dssConfig.monolithBaseUrl.isNullOrBlank(),
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
  val publicBaseUrl: String,
  val oauthRedirectPath: String,
)

@Serializable
private data class ApiCheckResponse(
  val shop: String?,
  val checks: ApiCheckDetails,
)

@Serializable
private data class ApiCheckDetails(
  val hasHeaderToken: Boolean,
  val hasTokenMappedForShop: Boolean,
  val demoRoutesEnabled: Boolean,
  val testHarnessEnabled: Boolean,
  val monolithConfigured: Boolean,
)
