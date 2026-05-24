package dropnext.dss.routing

import dropnext.dss.path.Paths
import dropnext.dss.handler.DemoHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Smoke-test routes only registered when `dssConfig.dev.enableDemoRoutes` is true (see app.kt wiring).
 */
fun Route.installDemoRoutes(handlers: DemoHandlers) {
  get(Paths.demoProducts) { handlers.handleListProducts(call) }
  get(Paths.demoOrder) { handlers.handleGetOrder(call) }
  post(Paths.demoFulfillmentCreate) { handlers.handleCreateFulfillment(call) }
  post(Paths.demoFulfillmentTracking) { handlers.handleUpdateTracking(call) }
}
