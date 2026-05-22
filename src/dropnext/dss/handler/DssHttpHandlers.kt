package dropnext.dss.handler

import dropnext.dss.GraphQLClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.DssPaths
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.lib.dss.ShopifyAdminToken
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dss.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateResponse
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.ktor.requireDssInternalSecret
import dropnext.dss.lib.dss.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.fulfillment.DssFulfillmentService
import dropnext.dss.lib.fulfillment.FulfillmentResult
import dropnext.dss.lib.fulfillment.RequestValidation
import dropnext.dss.lib.fulfillment.toHttpStatus
import dropnext.dss.lib.fulfillment.toMessage
import dropnext.dss.lib.fulfillment.validateSyncShipmentsRequest
import dropnext.dss.lib.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.lib.ktor.shopifyAccessTokenFromHeader
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.shopifySubdomainShort
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

class DssHttpHandlers(
  private val shopifyConfig: ShopifyConfig,
  private val dssConfig: DssAppConfig,
  private val gqlClientCache: GraphQLClientCache,
  private val fulfillmentService: DssFulfillmentService,
  private val monolithService: MonolithService? = null,
) {
  suspend fun handleSyncShipments(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<SyncShipmentsWithFulfillmentsRequest>()
    when (val v = validateSyncShipmentsRequest(body)) {
      is RequestValidation.Invalid ->
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = v.message))
      RequestValidation.Valid -> Unit
    }
    val shop = normalizeShopDomain(body.shopifySubdomain)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))
    if (dssConfig.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = listOf(9_000_000_000_000_001L)),
      )
    }
    val token = call.request.shopifyAccessTokenFromHeader()
        ?: when (
          val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, monolithService)
        ) {
          ShopifyAdminToken.Missing ->
            return call.respond(
              HttpStatusCode.Unauthorized,
              ErrorResponse(
                error = "missing Shopify Admin token: use header X-Shopify-Access-Token or configure DSS_SHOP_ACCESS_TOKENS",
              ),
            )
          is ShopifyAdminToken.Resolved -> t.token
        }
    val graphQLClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    when (val result = fulfillmentService.syncShipmentsWithFulfillments(graphQLClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val msg = result.toMessage()
        log.warn { "Failed: $msg" }
        call.respond(result.toHttpStatus(), ErrorResponse(error = msg))
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<TrackingUpdateRequest>()
    when (val v = validateTrackingUpdateRequest(body)) {
      is RequestValidation.Invalid ->
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = v.message))
      RequestValidation.Valid -> Unit
    }
    val shop =
      normalizeShopDomain(body.shopifySubdomain)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))
    if (dssConfig.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        TrackingUpdateResponse(fulfillmentEventId = 9_000_000_000_000_001L),
      )
    }
    val token = call.request.shopifyAccessTokenFromHeader()
        ?: when (
          val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, monolithService)
        ) {
          ShopifyAdminToken.Missing ->
            return call.respond(
              HttpStatusCode.Unauthorized,
              ErrorResponse(
                error = "missing Shopify Admin token: use header X-Shopify-Access-Token or configure DSS_SHOP_ACCESS_TOKENS",
              ),
            )
          is ShopifyAdminToken.Resolved -> t.token
        }
    val graphQLClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    when (val result = fulfillmentService.createTrackingEvent(graphQLClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val msg = result.toMessage()
        log.warn { "Failed: $msg" }
        call.respond(result.toHttpStatus(), ErrorResponse(error = msg))
      }
    }
  }

  /** `PUT` to [DssPaths.STORES_API_KEY] — caches a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<PutShopAccessTokenRequest>()
    val shop = normalizeShopDomain(body.shopifySubdomain)
      ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))

    dssConfig.shopAccessTokens[shop] = body.apiKey
    log.info { "PUT ${DssPaths.STORES_API_KEY}: token cached in memory for shop=$shop" }

    monolithService?.let { monolith ->
      val apiKeyReq = UpdateStoreApiKeyRequest(
        shopifySubdomain = shopifySubdomainShort(shop),
        shopifyShopId = body.shopifyShopId ?: 0L,
        apiKey = body.apiKey,
      )
      when (val r = monolith.putStoreApiKey(apiKeyReq)) {
        is StoreApiKeyResult.Ok -> log.info { "Monolith store api-key updated storeId=${r.storeId} shop=$shop" }
        is StoreApiKeyResult.Error ->
          logMonolithFailure("putStoreApiKey", r.status, r.parsed, "shop=$shop")
      }
    }

    call.respond(PutShopAccessTokenResponse(shop = shop))
  }
}
