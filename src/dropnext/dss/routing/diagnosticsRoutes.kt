package dropnext.dss.routing

import dropnext.dss.handler.DiagnosticsHandlers
import dropnext.dss.lib.ktor.MONOLITH_WEBHOOK_AUTH
import dropnext.dss.path.Paths
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get


fun Route.diagnosticsRoutes(handlers: DiagnosticsHandlers) {
  get(Paths.index) { handlers.handleIndex(call) }
  get(Paths.health) { handlers.handleHealth(call) }
  get(Paths.api) { handlers.handleApiStatus(call) }
  get(Paths.apiRedirectUrl) { handlers.handleRedirectUrl(call) }
  // The per-shop check drives a monolith lookup and a Shopify query, and says whether a shop is
  // installed: not for anyone who can reach the service.
  authenticate(MONOLITH_WEBHOOK_AUTH) {
    get(Paths.apiCheck) { handlers.handleApiCheck(call) }
  }
}
