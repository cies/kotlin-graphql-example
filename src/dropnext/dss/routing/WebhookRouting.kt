package dropnext.dss.routing

import dropnext.dss.path.DssPaths
import dropnext.dss.handler.ShopifyWebhookHandlers
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.installWebhookRoutes(handlers: ShopifyWebhookHandlers) {
  post(DssPaths.WEBHOOKS_SHOPIFY) { handlers.handleShopifyWebhook(call) }
}
