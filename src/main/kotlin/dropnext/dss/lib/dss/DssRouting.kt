package dropnext.dss.lib.dss

import io.ktor.server.application.Application
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * DSS inbound routes from DropNext monolith (see repo root **`openapi.json`** webhooks section for URL semantics).
 *
 * Per spec: **`SyncShipmentsWithFulfillmentsRequest`** is POSTed to **`/tracking-update`**;
 * **`TrackingUpdateRequest`** is POSTed to **`/sync-shipments-with-fulfillments`**. Plural **`/tracking-updates`**
 * is a backward-compatible alias for the tracking-update payload (same as `/sync-shipments-with-fulfillments`).
 */
fun Application.installDssRoutes(handlers: DssHttpHandlers) {
  routing {
    post("/sync-shipments-with-fulfillments") {
      handlers.handleTrackingUpdate(call, logLabel = "sync-shipments-with-fulfillments")
    }
    post("/tracking-updates") {
      handlers.handleTrackingUpdate(call, logLabel = "tracking-updates")
    }
    post("/tracking-update") {
      handlers.handleSyncShipments(call, logLabel = "tracking-update")
    }
  }
}
