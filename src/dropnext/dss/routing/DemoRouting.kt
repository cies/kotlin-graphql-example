package dropnext.dss.routing

import dropnext.dss.config.DssPaths
import dropnext.dss.handler.DemoHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Smoke-test routes only registered when `dssConfig.enableDemoRoutes` is true (see app.kt wiring).
 */
fun Route.installDemoRoutes(handlers: DemoHandlers) {
  get(DssPaths.DEMO_PRODUCTS) { handlers.handleListProducts(call) }
  get(DssPaths.DEMO_ORDER) { handlers.handleGetOrder(call) }
  post(DssPaths.DEMO_FULFILLMENT_CREATE) { handlers.handleCreateFulfillment(call) }
  post(DssPaths.DEMO_FULFILLMENT_TRACKING) { handlers.handleUpdateTracking(call) }
}
