package dropnext.dss.routing

import dropnext.dss.path.DssPaths
import dropnext.dss.handler.MonolithWebhookHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * DSS inbound routes called by the DropNext monolith (canonical contract: repo root **`openapi.json`**).
 *
 * Paths match their request bodies:
 * - **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **[DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS]**;
 * - **`TrackingUpdateRequest`** is POSTed to **[DssPaths.TRACKING_UPDATE]**;
 * - plural **[DssPaths.TRACKING_UPDATES]** is a backward-compatible alias for the tracking-update payload;
 * - **`PUT`** to **[DssPaths.STORES_API_KEY]** caches a Shopify Admin token and forwards it to the monolith.
 */
fun Route.installDssRoutes(handlers: MonolithWebhookHandlers) {
  post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) { handlers.handleSyncShipments(call) }
  post(DssPaths.TRACKING_UPDATE) { handlers.handleTrackingUpdate(call) }
  post(DssPaths.TRACKING_UPDATES) { handlers.handleTrackingUpdate(call) }
  put(DssPaths.STORES_API_KEY) { handlers.handlePutStoreApiKey(call) }
}
