package dropnext.dss.routing

import dropnext.dss.path.DssPaths
import dropnext.dss.handler.OAuthHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.installOAuthRoutes(handlers: OAuthHandlers, oauthCallbackPath: String) {
  get(DssPaths.INSTALL) { handlers.handleInstall(call) }
  get(oauthCallbackPath) { handlers.handleOAuthCallback(call) }
}
