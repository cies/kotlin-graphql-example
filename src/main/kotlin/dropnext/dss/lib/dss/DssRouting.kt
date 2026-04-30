package dropnext.dss.lib.dss

import io.ktor.server.application.Application
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** Wires DSS paths only; business logic lives in [DssHttpHandlers]. */
fun Application.installDssRoutes(handlers: DssHttpHandlers) {
  routing {
    post("/sync-shipments-with-fulfillments") {
      handlers.handleSyncShipments(call, logLabel = "sync-shipments")
    }
    post("/tracking-updates") {
      handlers.handleTrackingUpdate(call, logLabel = "tracking-updates")
    }
    post("/tracking-update") {
      handlers.handleTrackingUpdate(call, logLabel = "tracking-update")
    }
    post("/dummy1") {
      handlers.handleTrackingUpdate(call, logLabel = "dummy1")
    }
    post("/dummy2") {
      handlers.handleSyncShipments(call, logLabel = "dummy2")
    }
  }
}
