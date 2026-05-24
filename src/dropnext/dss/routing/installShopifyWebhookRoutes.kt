package dropnext.dss.routing

import dropnext.dss.path.Paths
import dropnext.dss.handler.ShopifyWebhookHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.installShopifyWebhookRoutes(handlers: ShopifyWebhookHandlers) {
  post(Paths.webhooksShopify) { handlers.handleShopifyWebhook(call) }
}
