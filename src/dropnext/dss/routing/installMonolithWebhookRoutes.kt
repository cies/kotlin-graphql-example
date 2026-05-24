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
 * call an auth helper themselves — a missing/invalid `X-DSS-Internal-Secret` short-circuits with
 * a `401 unauthorized` before the handler runs. The matching auth provider is installed in
 * `app.kt` via `installDssInternalSecretAuth(...)`.
 *
 * Paths match their request bodies:
 * - **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **[Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS]**;
 * - **`TrackingUpdateRequest`** is POSTed to **[Paths.TRACKING_UPDATE]**;
 * - plural **[Paths.TRACKING_UPDATES]** is a backward-compatible alias for the tracking-update payload;
 * - **`PUT`** to **[Paths.STORES_API_KEY]** caches a Shopify Admin token and forwards it to the monolith.
 */
fun Route.installMonolithWebhookRoutes(handlers: MonolithWebhookHandlers) {
  requireMonolithWebhookAuthHeader {
    post(Paths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) { handlers.handleSyncShipments(call) }
    post(Paths.TRACKING_UPDATE) { handlers.handleTrackingUpdate(call) }
    post(Paths.TRACKING_UPDATES) { handlers.handleTrackingUpdate(call) }
    put(Paths.STORES_API_KEY) { handlers.handlePutStoreApiKey(call) }
  }
}
