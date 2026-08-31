package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.valueOrNull
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
import dropnext.dss.lib.shopify.graphql.fulfillment.SyncShipmentsRunStats
import dropnext.dss.lib.shopify.graphql.fulfillment.formatSyncShipmentsLogLine
import dropnext.dss.lib.shopify.graphql.fulfillment.toDssError
import dropnext.dss.lib.shopify.graphql.fulfillment.validate
import dropnext.dss.lib.shopify.graphql.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.path.Paths
import dropnext.dss.workflow.ShopifyMutation
import dropnext.dss.workflow.determineShopifyMutations
import dropnext.dss.workflow.effectShopifyMutations
import dropnext.dss.workflow.syncShopifyTrackingEvent
import dropnext.dss.workflow.toDssError
import io.github.oshai.kotlinlogging.KotlinLogging
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
    val shopifyGqlService = shopifyGraphqlServiceFactory.forShop(shop)
    if (shopifyGqlService == null) {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }

    val determinedMutationsResult = determineShopifyMutations(
      shopifyGqlService = shopifyGqlService,
      shopifyOrderId = syncRequest.shopifyOrderId,
      shipments = syncRequest.shipments,
    )
    val determinedMutations = when (determinedMutationsResult) {
      is Failure -> {
        val mapped = determinedMutationsResult.reason.toDssError()
        log.warn { "sync-shipments failed shop=${shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
        return
      }

      is Success -> determinedMutationsResult.value
    }


    when (val effected = effectShopifyMutations(shopifyGqlService, determinedMutations)) {
      is Failure -> {
        val mapped = effected.reason.toDssError()
        log.warn { "sync-shipments failed shop=${shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
        return
      }

      is Success -> {

        val stats = SyncShipmentsRunStats(
          canceledCount = determinedMutations.count { it is ShopifyMutation.FulfillmentCancel },
          createdCount = effected.value.size,
          skippedLines = 0,
          skippedShipments = syncRequest.shipments.size -
            determinedMutations.count { it is ShopifyMutation.FulfillmentCreate },
        )

        log.info {
          formatSyncShipmentsLogLine(
            shop.subdomainOnly,
            syncRequest.shopifyOrderId,
            stats,
            effected.value,
          )
        }
        call.respond(
          SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = effected.value),
        )
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val body = call.receiveOr400<TrackingUpdateRequest>() ?: return
    if (!call.validateOrRespond(validation = validateTrackingUpdateRequest(body))) return
    val shop = call.normalizeShopOrRespond(rawShop = body.shopifySubdomain) ?: return
    val shopify = shopifyGraphqlServiceFactory.forShop(shop)
    if (shopify == null) {
      call.respondError(DssError.MissingShopifyAdminToken)
      return
    }
    when (val result = syncShopifyTrackingEvent(shopify, body)) {
      is FulfillmentResult.Ok ->
        call.respond<TrackingUpdateResponse>(result.value)

      is FulfillmentResult.Err -> {
        val mapped = result.toDssError()
        log.warn { "tracking-update failed shop=${shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
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
  private suspend fun ApplicationCall.normalizeShopOrRespond(rawShop: String)

    : ShopDomain
  ? =
    ShopDomain.parse(rawShop) ?: run {
      respondError(DssError.InvalidParameter("shopify_subdomain"))
      null
    }
}
