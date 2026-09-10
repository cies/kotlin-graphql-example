package dropnext.dss.routing

import dropnext.dss.handler.MonolithWebhookHandlers
import dropnext.dss.lib.ktor.MONOLITH_WEBHOOK_AUTH
import dropnext.dss.path.Paths
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put


/**
 * DSS inbound routes called by the DropNext monolith (canonical contract: `src/resources/monolith-dss-openapi.json`).
 *
 * Every route mounted here sits inside `authenticate(MONOLITH_WEBHOOK_AUTH)`, so handlers never have to check
 * auth themselves: a missing or wrong `Authorization: Bearer ...` is answered with the bearer challenge before
 * the handler runs. The provider is installed in `dssModule.kt` via [dropnext.dss.lib.ktor.installMonolithWebhookAuth].
 *
 * Paths match their request bodies:
 * - **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **[Paths.syncShipmentsWithFulfillments]**;
 * - **`TrackingUpdateRequest`** is POSTed to **[Paths.trackingUpdate]**;
 * - **`PUT`** to **[Paths.storesApiKey]** caches a Shopify Admin token and forwards it to the monolith.
 */
fun Route.monolithWebhookRoutes(handlers: MonolithWebhookHandlers) {
  authenticate(MONOLITH_WEBHOOK_AUTH) {
    post(Paths.syncShipmentsWithFulfillments) { handlers.handleSyncShipments(call) }
    post(Paths.trackingUpdate) { handlers.handleTrackingUpdate(call) }
    put(Paths.storesApiKey) { handlers.handlePutStoreApiKey(call) }
  }
}
