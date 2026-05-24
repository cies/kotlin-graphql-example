package dropnext.dss.handler

import dropnext.dss.path.Paths
import dropnext.dss.lib.monolith.dto.generated.PutShopAccessTokenRequest
import dropnext.dss.lib.monolith.dto.generated.PutShopAccessTokenResponse
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyRequest
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
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
import dropnext.dss.workflow.syncShopifyShipmentsToFulfillments
import dropnext.dss.workflow.syncShopifyTrackingEvent
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
    runFulfillmentRequest(
      call = call,
      subdomain = body.shopifySubdomain,
      validation = validateSyncShipmentsRequest(body),
      operation = "sync-shipments",
    ) { syncShopifyShipmentsToFulfillments(it, body) }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val body = call.receiveOr400<TrackingUpdateRequest>() ?: return
    runFulfillmentRequest(
      call = call,
      subdomain = body.shopifySubdomain,
      validation = validateTrackingUpdateRequest(body),
      operation = "tracking-update",
    ) { syncShopifyTrackingEvent(it, body) }
  }

  /**
   * Common shape for the two fulfillment-flavored monolith webhooks: validate → resolve shop →
   * resolve Admin token → invoke [block] and translate its [FulfillmentResult] to the appropriate
   * `respond` / `respondError` outcome.
   */
  private suspend inline fun <reified T : Any> runFulfillmentRequest(
    call: ApplicationCall,
    subdomain: String,
    validation: RequestValidation,
    operation: String,
    block: suspend (ShopifyGraphqlService) -> FulfillmentResult<T>,
  ) {
    if (!call.validateOrRespond(validation)) return
    val shop = call.normalizeShopOrRespond(subdomain) ?: return
    val shopify = shopifyGraphqlServiceFactory.forShop(shop) ?: run {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }
    when (val result = block(shopify)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "$operation failed shop=${shop.host}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  /** `PUT` to [Paths.storesApiKey] — caches a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    val body = call.receiveOr400<PutShopAccessTokenRequest>() ?: return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return

    shopTokenCache[shop] = body.apiKey
    log.info { "PUT ${Paths.storesApiKey}: token cached in memory for shop=${shop.host}" }

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
