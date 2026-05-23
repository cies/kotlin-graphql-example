package dropnext.dss.handler

import dropnext.dss.GraphqlClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.config.ShopifyConfig
import dropnext.dss.lib.auth.ShopAccessTokenCache
import dropnext.dss.lib.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.auth.tokenOrNull
import dropnext.dss.lib.fulfillment.DssFulfillmentService
import dropnext.dss.lib.fulfillment.FulfillmentResult
import dropnext.dss.lib.fulfillment.RequestValidation
import dropnext.dss.lib.fulfillment.toDssError
import dropnext.dss.lib.fulfillment.validateSyncShipmentsRequest
import dropnext.dss.lib.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.receiveOr400
import dropnext.dss.lib.ktor.requireDssInternalSecret
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.shopifySubdomainShort
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

class DssHttpHandlers(
  private val shopifyConfig: ShopifyConfig,
  private val dssConfig: DssAppConfig,
  private val gqlClientCache: GraphqlClientCache,
  private val fulfillmentService: DssFulfillmentService,
  private val monolithService: MonolithService? = null,
  private val shopTokens: ShopAccessTokenCache,
) {
  suspend fun handleSyncShipments(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receiveOr400<SyncShipmentsWithFulfillmentsRequest>() ?: return
    if (!call.validateOrRespond(validateSyncShipmentsRequest(body))) return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = listOf(9_000_000_000_000_001L)),
      )
    }
    val token = call.resolveShopifyAdminToken(shop, shopTokens, monolithService).tokenOrNull
      ?: return call.respondError(DssError.MissingShopifyAdminToken)
    val gqlClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    when (val result = fulfillmentService.syncShipmentsWithFulfillments(gqlClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "Failed: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receiveOr400<TrackingUpdateRequest>() ?: return
    if (!call.validateOrRespond(validateTrackingUpdateRequest(body))) return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        TrackingUpdateResponse(fulfillmentEventId = 9_000_000_000_000_001L),
      )
    }
    val token = call.resolveShopifyAdminToken(shop, shopTokens, monolithService).tokenOrNull
      ?: return call.respondError(DssError.MissingShopifyAdminToken)
    val gqlClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    when (val result = fulfillmentService.createTrackingEvent(gqlClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "Failed: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  /** `PUT` to [DssPaths.STORES_API_KEY] — caches a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receiveOr400<PutShopAccessTokenRequest>() ?: return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return

    shopTokens[shop] = body.apiKey
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

  /** Returns `true` when [validation] is valid; otherwise responds 400 with the accumulated messages and returns `false`. */
  private suspend fun ApplicationCall.validateOrRespond(validation: RequestValidation): Boolean =
    when (validation) {
      RequestValidation.Valid -> true
      is RequestValidation.Invalid -> {
        respondError(DssError.InvalidRequest(validation.message))
        false
      }
    }

  /** Normalises [rawShop] to a `*.myshopify.com` host, or responds 400 and returns `null`. */
  private suspend fun ApplicationCall.normalizeShopOrRespond(rawShop: String): String? =
    normalizeShopDomain(rawShop) ?: run {
      respondError(DssError.InvalidParameter("shopify_subdomain"))
      null
    }
}
