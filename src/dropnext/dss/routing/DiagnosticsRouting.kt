package dropnext.dss.routing

import dropnext.dss.config.DssPaths
import dropnext.dss.handler.DiagnosticsHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.installDiagnosticsRoutes(handlers: DiagnosticsHandlers) {
  get(DssPaths.INDEX) { handlers.handleIndex(call) }
  get(DssPaths.HEALTH) { handlers.handleHealth(call) }
  get(DssPaths.API) { handlers.handleApiStatus(call) }
  get(DssPaths.API_CHECK) { handlers.handleApiCheck(call) }
  get(DssPaths.API_REDIRECT_URL) { handlers.handleRedirectUrl(call) }
}
