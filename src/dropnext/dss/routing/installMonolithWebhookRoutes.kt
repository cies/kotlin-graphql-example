package dropnext.dss.routing

import dropnext.dss.handler.MonolithWebhookHandlers
import dropnext.dss.lib.ktor.plugin.requireMonolithWebhookAuthHeader
import dropnext.dss.path.Paths
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * DSS inbound routes called by the DropNext monolith (canonical contract: repo root **`openapi.json`**).
 *
 * Every route mounted here sits inside [requireMonolithWebhookAuthHeader], so handlers never have to
 * call an auth helper themselves - a missing/invalid `Authorization: Bearer ...` short-circuits with
 * a `401 unauthorized` before the handler runs. The matching auth provider is installed in
 * `app.kt` via [dropnext.dss.lib.ktor.plugin.installMonolithWebhookAuth].
 *
 * Paths match their request bodies:
 * - **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **[Paths.syncShipmentsWithFulfillments]**;
 * - **`TrackingUpdateRequest`** is POSTed to **[Paths.trackingUpdate]**;
 * - **`PUT`** to **[Paths.storesApiKey]** caches a Shopify Admin token and forwards it to the monolith.
 */
fun Route.installMonolithWebhookRoutes(handlers: MonolithWebhookHandlers) {
  requireMonolithWebhookAuthHeader {
    post(Paths.syncShipmentsWithFulfillments) { handlers.handleSyncShipments(call) }
    post(Paths.trackingUpdate) { handlers.handleTrackingUpdate(call) }
    put(Paths.storesApiKey) { handlers.handlePutStoreApiKey(call) }
  }
}
