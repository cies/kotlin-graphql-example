package dropnext.dss.handler

import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.receiveOr400
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateResponse
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyRequest
import dropnext.dss.lib.monolith.dto.generated.UpdateStoreApiKeyResponse
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.RequestValidation
import dropnext.dss.lib.shopify.graphql.fulfillment.toDssError
import dropnext.dss.lib.shopify.graphql.fulfillment.validate
import dropnext.dss.lib.shopify.graphql.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.path.Paths
import dropnext.dss.workflow.syncShopifyShipmentsToFulfillments
import dropnext.dss.workflow.syncShopifyTrackingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/**
 * Handlers for the monolith's webhook REST endpoints.
 * Internal-secret verification is enforced by the
 * [dropnext.dss.lib.ktor.plugin.requireMonolithWebhookAuthHeader] route guard
 * in [dropnext.dss.routing.installMonolithWebhookRoutes],
 * so these methods can focus on the business logic.
 *
 * Per-shop access-token resolution and Graphql wiring live inside [ShopifyGraphqlServiceFactory];
 * handlers call [ShopifyGraphqlServiceFactory.forShop] and respond with
 * [DssError.MissingShopifyAdminToken] when no Admin token is available.
 */
class MonolithWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopAccessTokenCache: ShopAccessTokenCache,
) {

  suspend fun handleSyncShipments(call: ApplicationCall) {
    val syncRequest = call.receiveOr400<SyncShipmentsWithFulfillmentsRequest>() ?: return
    val validationResult = syncRequest.validate()
    if (validationResult is RequestValidation.Invalid) {
      call.respondError(DssError.InvalidRequest(validationResult.message))
      return
    }
    val shop = call.normalizeShopOrRespond(rawShop = syncRequest.shopifySubdomain) ?: return
    val shopifyGqlService = shopifyGraphqlServiceFactory.forShop(shop) ?: this.run {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }
    // Step 1: determine what has to be changed in Shopify, delivers the data structure describing this, this is READ ONLY.
    // fun determineShopifyMutations(shopifyGqlService, syncRequest.shopifyOrderId, syncRequest.shipments):
    //         Result4k<List<ShopifyMutation>, DetermineShopifyMutationsError>
    // ShopifyMutation: FulfillmentCancel(id) or FulfillmentCreate(List<FulfillmentOrderLineItem>, trackingNumber, notifyUser)
    // FulfillmentOrderLineItem(foId, lineItemId)

    // Step 2: respond with an error in case determineShopifyMutations returned an error, else continue

    // Step 3: effect the changes from step 1 in Shopify, DESTRUCTIVE.

    // Step 4: respond with an HTTP error status or an OK/success, based off of the result of step 3.

    // fun determineShopifyMutations(...): Result4k<List<ShopifyMutation>, DetermineShopifyMutationsError> {
    //       // pull in all the relevant data

    //       // calculate the result (pure: only works on it's input values w/o any side effects)
    //       return calculateShopifyMutations(...)
    // }

    // /** This is a pure function, so it can be easily tested. */
    // fun calculateShopifyMutations(...): Result4k<List<ShopifyMutation>, DetermineShopifyMutationsError>

    // fun effectShopifyMutations(...): List<ShopifyError> // where empty list means success...

    when (val result = syncShopifyShipmentsToFulfillments(shopifyGqlService, syncRequest)) {
      // Workflows that return no body use `Unit` (e.g. legacy no-op responses). Typed responses
      // (e.g. sync-shipments `new_fulfillment_ids`, tracking-update `fulfillment_event_id`) are JSON-serialized.
      is FulfillmentResult.Ok ->
        if (result.value == Unit) call.respond<HttpStatusCode>(HttpStatusCode.OK)
        else call.respond<SyncShipmentsWithFulfillmentsResponse>(result.value)

      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "${"sync-shipments"} failed shop=${shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }

  }


  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val body = call.receiveOr400<TrackingUpdateRequest>() ?: return
    if (call.validateOrRespond(validation = validateTrackingUpdateRequest(body))) {
      val shop = call.normalizeShopOrRespond(rawShop = body.shopifySubdomain)
      if (shop != null) {
        val shopify = shopifyGraphqlServiceFactory.forShop(shop) ?: this.run {
          call.respondError(DssError.MissingShopifyAdminToken)
          return
        }
        when (val result = syncShopifyTrackingEvent(shopify, body)) {
          // Workflows that return no body use `Unit` (e.g. legacy no-op responses). Typed responses
          // (e.g. sync-shipments `new_fulfillment_ids`, tracking-update `fulfillment_event_id`) are JSON-serialized.
          is FulfillmentResult.Ok ->
            if (result.value == Unit) call.respond<HttpStatusCode>(HttpStatusCode.OK)
            else call.respond<TrackingUpdateResponse>(result.value)

          is FulfillmentResult.Err -> {
            val mapped = result.toDssError()
            log.warn { "${"tracking-update"} failed shop=${shop.normalizedShopifyHost}: ${mapped.message}" }
            call.respondError(mapped)
          }
        }
      }
    }
  }

  /** `PUT` to [Paths.storesApiKey] — caches a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    val body = call.receiveOr400<UpdateStoreApiKeyRequest>() ?: return
    val shop = call.normalizeShopOrRespond(body.shopifySubdomain) ?: return

    shopAccessTokenCache[shop] = body.apiKey
    log.info { "PUT ${Paths.storesApiKey}: token cached in memory for shop=${shop.normalizedShopifyHost}" }

    val apiKeyReq = UpdateStoreApiKeyRequest(
      shopifySubdomain = shop.subdomainOnly,
      shopifyShopId = body.shopifyShopId,
      apiKey = body.apiKey,
    )
    val storeId = when (val r = monolithService.putStoreApiKey(apiKeyReq)) {
      is StoreApiKeyResult.Ok -> {
        log.info { "Monolith store api-key updated storeId=${r.storeId} shop=${shop.normalizedShopifyHost}" }
        r.storeId
      }

      is StoreApiKeyResult.Error -> {
        logMonolithFailure(
          "putStoreApiKey",
          r.status,
          r.parsed,
          "shop=${shop.normalizedShopifyHost}"
        )
        0L
      }
    }

    call.respond(UpdateStoreApiKeyResponse(storeId = storeId))
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
