package dropnext.dss.handler

import dropnext.dss.path.Paths
import dropnext.dss.lib.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.RequestValidation
import dropnext.dss.lib.shopify.graphql.fulfillment.toDssError
import dropnext.dss.lib.shopify.graphql.fulfillment.validateSyncShipmentsRequest
import dropnext.dss.lib.shopify.graphql.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.receiveOr400
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.ShopDomain
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/**
 * Handlers for the DSS internal REST endpoints called by the monolith. Internal-secret
 * verification is enforced by the [dropnext.dss.lib.ktor.plugin.requireMonolithWebhookAuthHeader] route guard
 * in [dropnext.dss.routing.installMonolithWebhookRoutes], so these methods can focus on the business logic.
 *
 * Per-shop access-token resolution + Graphql wiring live inside [ShopifyGraphqlServiceFactory]; handlers
 * call [ShopifyGraphqlServiceFactory.forShop] and respond with [DssError.MissingShopifyAdminToken] when
 * no Admin token is available.
 */
class MonolithWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopTokenCache: ShopAccessTokenCache,
) {
  suspend fun handleSyncShipments(call: ApplicationCall) {
    val body = call.receiveOr400<SyncShipmentsWithFulfillmentsRequest>() ?: return
    if (!call.validateOrRespond(validateSyncShipmentsRequest(body))) return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return
    val shopify = shopifyGraphqlServiceFactory.forShop(shop) ?: run {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }

    when (val result = shopify.syncShipmentsWithFulfillments(body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "sync-shipments failed shop=${shop.host}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val body = call.receiveOr400<TrackingUpdateRequest>() ?: return
    if (!call.validateOrRespond(validateTrackingUpdateRequest(body))) return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return
    val shopify = shopifyGraphqlServiceFactory.forShop(shop) ?: run {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }

    when (val result = shopify.createTrackingEvent(body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "tracking-update failed shop=${shop.host}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  /** `PUT` to [Paths.STORES_API_KEY] — caches a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    val body = call.receiveOr400<PutShopAccessTokenRequest>() ?: return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return

    shopTokenCache[shop] = body.apiKey
    log.info { "PUT ${Paths.STORES_API_KEY}: token cached in memory for shop=${shop.host}" }

    val apiKeyReq = UpdateStoreApiKeyRequest(
      shopifySubdomain = shop.subdomainShort,
      shopifyShopId = body.shopifyShopId ?: 0L,
      apiKey = body.apiKey,
    )
    when (val r = monolithService.putStoreApiKey(apiKeyReq)) {
      is StoreApiKeyResult.Ok ->
        log.info { "Monolith store api-key updated storeId=${r.storeId} shop=${shop.host}" }
      is StoreApiKeyResult.Error ->
        logMonolithFailure("putStoreApiKey", r.status, r.parsed, "shop=${shop.host}")
    }

    call.respond(PutShopAccessTokenResponse(shop = shop.host))
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

  /** Parses [rawShop] to a [ShopDomain], or responds 400 and returns `null`. */
  private suspend fun ApplicationCall.normalizeShopOrRespond(rawShop: String): ShopDomain? =
    ShopDomain.parse(rawShop) ?: run {
      respondError(DssError.InvalidParameter("shopify_subdomain"))
      null
    }
}
