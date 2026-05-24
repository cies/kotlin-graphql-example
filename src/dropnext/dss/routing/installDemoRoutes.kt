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
  get(Paths.DEMO_PRODUCTS) { handlers.handleListProducts(call) }
  get(Paths.DEMO_ORDER) { handlers.handleGetOrder(call) }
  post(Paths.DEMO_FULFILLMENT_CREATE) { handlers.handleCreateFulfillment(call) }
  post(Paths.DEMO_FULFILLMENT_TRACKING) { handlers.handleUpdateTracking(call) }
}
