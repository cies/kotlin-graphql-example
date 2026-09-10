package dropnext.dss.routing

import dropnext.dss.path.Paths
import dropnext.dss.handler.DiagnosticsHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get


fun Route.diagnosticsRoutes(handlers: DiagnosticsHandlers) {
  get(Paths.index) { handlers.handleIndex(call) }
  get(Paths.health) { handlers.handleHealth(call) }
  get(Paths.api) { handlers.handleApiStatus(call) }
  get(Paths.apiCheck) { handlers.handleApiCheck(call) }
  get(Paths.apiRedirectUrl) { handlers.handleRedirectUrl(call) }
}
