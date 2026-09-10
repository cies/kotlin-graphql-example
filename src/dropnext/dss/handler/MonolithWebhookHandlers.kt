package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.TrackingUpdateResponse
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.workflow.persistTokenToMonolith
import dropnext.dss.workflow.syncShopifyShipmentsToFulfillments
import dropnext.dss.workflow.syncShopifyTrackingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/**
 * Handlers for the monolith's webhook REST endpoints: resolve the shop, hand the request to a workflow
 * and map its answer onto the response. Everything before that is the framework's: the bearer token is
 * checked by the `authenticate` block in [dropnext.dss.routing.monolithWebhookRoutes], and a body that
 * does not decode or does not validate is answered by `StatusPages` before `receive` returns.
 */
class MonolithWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopTokens: ShopTokenStore,
) {

  suspend fun handleSyncShipments(call: ApplicationCall) {
    val request = call.receive<SyncShipmentsWithFulfillmentsRequest>()
    val shopify = call.shopifyServiceOrRespond(request.shopifySubdomain) ?: return

    when (val synced = syncShopifyShipmentsToFulfillments(shopify, request)) {
      is Success ->
        call.respond(SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = synced.value.map { it.value }))

      is Failure -> {
        val mapped = synced.reason.toDssError()
        log.warn { "sync-shipments failed shop=${shopify.shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  suspend fun handleTrackingUpdate(call: ApplicationCall) {
    val request = call.receive<TrackingUpdateRequest>()
    val shopify = call.shopifyServiceOrRespond(request.shopifySubdomain) ?: return

    when (val synced = syncShopifyTrackingEvent(shopify, request)) {
      is Success ->
        call.respond(TrackingUpdateResponse(fulfillmentEventId = synced.value.value))

      is Failure -> {
        val mapped = synced.reason.toDssError()
        log.warn { "tracking-update failed shop=${shopify.shop.normalizedShopifyHost}: ${mapped.message}" }
        call.respondError(mapped)
      }
    }
  }

  /** `PUT /stores/api-key` — remembers a Shopify Admin token and forwards it to the monolith. */
  suspend fun handlePutStoreApiKey(call: ApplicationCall) {
    val request = call.receive<UpdateStoreApiKeyRequest>()
    val shop = call.shopDomainOrRespond(request.shopifySubdomain, "shopify_subdomain") ?: return
    val token = ShopifyAdminToken(request.apiKey)

    shopTokens.remember(shop, token)
    log.info { "PUT stores/api-key: token cached in memory for shop=${shop.normalizedShopifyHost}" }

    // The monolith is answered its own store id back, or `0` when it could not be reached: the
    // token is cached either way, which is what this endpoint is for.
    val storeId = when (val persisted = persistTokenToMonolith(monolithService, shop, ShopifyShopId(request.shopifyShopId), token)) {
      is MonolithPersistOutcome.Persisted -> persisted.storeId.value
      is MonolithPersistOutcome.Failed -> 0L
    }
    call.respond(UpdateStoreApiKeyResponse(storeId = storeId))
  }

  /** The shop's Graphql service, or the `400` / `401` that explains why there is none. */
  private suspend fun ApplicationCall.shopifyServiceOrRespond(rawShop: String): ShopifyGraphqlService? {
    val shop = shopDomainOrRespond(rawShop, "shopify_subdomain") ?: return null
    return shopifyGraphqlServiceFactory.forShop(shop) ?: run {
      respondError(DssError.MissingShopifyAdminToken)
      null
    }
  }
}
