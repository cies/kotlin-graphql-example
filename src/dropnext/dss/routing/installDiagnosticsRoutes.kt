package dropnext.dss.routing

import dropnext.dss.path.Paths
import dropnext.dss.handler.DiagnosticsHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get


fun Route.installDiagnosticsRoutes(handlers: DiagnosticsHandlers) {
  get(Paths.INDEX) { handlers.handleIndex(call) }
  get(Paths.HEALTH) { handlers.handleHealth(call) }
  get(Paths.API) { handlers.handleApiStatus(call) }
  get(Paths.API_CHECK) { handlers.handleApiCheck(call) }
  get(Paths.API_REDIRECT_URL) { handlers.handleRedirectUrl(call) }
}
