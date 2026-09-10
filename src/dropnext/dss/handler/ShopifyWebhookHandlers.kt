package dropnext.dss.handler

import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.lib.shopify.webhook.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.lib.shopify.webhook.shopDomainFromWebhook
import dropnext.dss.lib.shopify.webhook.variantIdsFromProductWebhook
import dropnext.dss.workflow.deleteShopifyProductFromMonolith
import dropnext.dss.workflow.syncShopifyOrderToMonolith
import dropnext.dss.workflow.syncShopifyProductToMonolith
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/**
 * The one inbound Shopify webhook endpoint: verifies the body signature, resolves the shop and its
 * service, and dispatches on the topic to the workflow that mirrors the change into the monolith.
 * Every verified delivery is answered `200`, whatever happened downstream: Shopify would otherwise
 * keep retrying a delivery we cannot act on any better the next time.
 */
class ShopifyWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopifyHmacVerifierService: ShopifyHmacVerifierService,
) {

  suspend fun handleShopifyWebhook(call: ApplicationCall) {
    val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
    val topic = ShopifyWebhookTopic.parse(call.request.headers["X-Shopify-Topic"])
    val shopDomainHeader = call.request.headers["X-Shopify-Shop-Domain"]
    val body = call.receive<ByteArray>()
    if (!shopifyHmacVerifierService.verifyWebhook(hmacHeader, body)) {
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    val bodyString = body.decodeToString()
    log.info { "Webhook verified topic=${topic.raw} shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}" }

    val shop = shopDomainFromWebhook(shopDomainHeader, bodyString)
    val shopify = shop?.let { shopifyGraphqlServiceFactory.forShop(it) }
    if (shop == null || shopify == null) {
      log.error {
        "Webhook: no Admin token topic=${topic.raw} shopDomainHeader=$shopDomainHeader shop=$shop " +
          "(configure DSS_SHOP_ACCESS_TOKENS or complete the OAuth install)"
      }
      call.respond(HttpStatusCode.OK)
      return
    }

    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate ->
        resourceGidOrLog(topic, bodyString)?.let { syncShopifyProductToMonolith(shopify, monolithService, it) }

      ShopifyWebhookTopic.ProductsDelete ->
        deleteShopifyProductFromMonolith(monolithService, shop, variantIdsFromProductWebhook(bodyString))

      ShopifyWebhookTopic.OrdersCreate ->
        resourceGidOrLog(topic, bodyString)?.let { syncShopifyOrderToMonolith(shopify, monolithService, it, topic.raw) }

      // The `create` already carried the order; an update is acknowledged, not mirrored.
      ShopifyWebhookTopic.OrdersUpdated ->
        log.info { "Webhook ${topic.raw} acknowledged (not mirrored)" }

      is ShopifyWebhookTopic.Other ->
        log.info { "Webhook topic not handled: ${topic.raw}" }
    }
    call.respond(HttpStatusCode.OK)
  }

  private fun resourceGidOrLog(topic: ShopifyWebhookTopic, bodyString: String): String? =
    graphqlResourceIdFromShopifyWebhook(topic.raw, bodyString)
      ?: run {
        log.warn { "Webhook ${topic.raw}: could not parse Graphql id from body" }
        null
      }
}
