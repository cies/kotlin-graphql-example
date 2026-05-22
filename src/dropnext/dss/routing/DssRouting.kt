package dropnext.dss.routing

import dropnext.dss.config.DssPaths
import dropnext.dss.handler.DssHttpHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * DSS inbound routes called by the DropNext monolith (canonical contract: repo root **`openapi.json`**).
 *
 * Per spec:
 * - **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **[DssPaths.TRACKING_UPDATE]**;
 * - **`TrackingUpdateRequest`** is POSTed to **[DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS]**;
 * - plural **[DssPaths.TRACKING_UPDATES]** is a backward-compatible alias for the tracking-update payload;
 * - **`PUT`** to **[DssPaths.STORES_API_KEY]** caches a Shopify Admin token and forwards it to the monolith.
 */
fun Route.installDssRoutes(handlers: DssHttpHandlers) {
  post(DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS) { handlers.handleTrackingUpdate(call) }
  post(DssPaths.TRACKING_UPDATES) { handlers.handleTrackingUpdate(call) }
  post(DssPaths.TRACKING_UPDATE) { handlers.handleSyncShipments(call) }
  put(DssPaths.STORES_API_KEY) { handlers.handlePutStoreApiKey(call) }
}
