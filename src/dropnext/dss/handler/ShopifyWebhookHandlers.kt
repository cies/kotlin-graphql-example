package dropnext.dss.handler

import dropnext.dss.lib.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.monolith.UpsertVariantsResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.lib.shopify.webhook.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.lib.shopify.webhook.shopDomainFromWebhookBody
import dropnext.dss.lib.shopify.webhook.variantLegacyIdsFromProductWebhook
import dropnext.dss.shopify.toProductVariantItems
import dropnext.dss.workflow.syncShopifyOrderToMonolith
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/** Handler for `POST` to [dropnext.dss.path.Paths.WEBHOOKS_SHOPIFY] — verifies HMAC and dispatches per topic. */
class ShopifyWebhookHandlers(
  private val syncOrderOnUpdated: Boolean,
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
    val bodyStr = body.decodeToString()
    log.info { "Webhook verified topic=${topic.raw} shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}" }

    val shop = ShopDomain.fromWebhook(shopDomainHeader, shopDomainFromWebhookBody(bodyStr))
    val shopify = shop?.let { shopifyGraphqlServiceFactory.forShop(it) }
    if (shop == null || shopify == null) {
      log.error {
        "Webhook: no Admin token topic=${topic.raw} shopDomainHeader=$shopDomainHeader shop=$shop " +
          "(configure DSS_SHOP_ACCESS_TOKENS or OAuth env entry)"
      }
      call.respond(HttpStatusCode.OK)
      return
    }

    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate ->
        handleProductUpsert(shopify, bodyStr, topic.raw)
      ShopifyWebhookTopic.ProductsDelete ->
        handleProductDelete(shop, bodyStr)
      ShopifyWebhookTopic.OrdersCreate ->
        handleOrderWebhook(shopify, bodyStr, topic.raw, syncToMonolith = true)
      ShopifyWebhookTopic.OrdersUpdated ->
        handleOrderWebhook(shopify, bodyStr, topic.raw, syncToMonolith = syncOrderOnUpdated)
      is ShopifyWebhookTopic.Other ->
        log.info { "Webhook topic not handled: ${topic.raw}" }
    }
    call.respond(HttpStatusCode.OK)
  }

  private suspend fun handleProductUpsert(
    shopify: ShopifyGraphqlService,
    bodyStr: String,
    topic: String,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook product: could not parse Graphql id from body" }
      return
    }
    val r = shopify.getProductById(id)
    val product = r.data?.product
    log.info { "Webhook product synced id=$id title=${product?.title} errors=${r.errors}" }
    if (product == null) return

    val currencyCode = r.data?.shop?.currencyCode?.name ?: "USD"
    val variantItems = product.toProductVariantItems(currencyCode)
    if (variantItems.isEmpty()) return

    val req = UpsertProductVariantsRequest(
      shopifySubdomain = shopify.shop.subdomainShort,
      productVariants = variantItems,
    )
    when (val result = monolithService.upsertProductVariants(req)) {
      is UpsertVariantsResult.Ok ->
        log.info { "Monolith upsert variants ok: ${result.upserted} upserted shop=${shopify.shop.host}" }
      is UpsertVariantsResult.Error -> {
        val msg = "Monolith upsert variants failed: status=${result.status} ${result.errorMessage}"
        if (result.status >= 500) log.error { msg } else log.warn { msg }
      }
    }
  }

  private suspend fun handleProductDelete(shop: ShopDomain, bodyStr: String) {
    val id = graphqlResourceIdFromShopifyWebhook("products/delete", bodyStr)
    log.info { "Webhook product deleted id=$id" }

    val variantIds = variantLegacyIdsFromProductWebhook(bodyStr)
    if (variantIds.isEmpty()) return

    val req = DeleteProductVariantsRequest(
      shopifySubdomain = shop.subdomainShort,
      productVariantIds = variantIds,
    )
    when (val result = monolithService.deleteProductVariants(req)) {
      is DeleteVariantsResult.Ok ->
        log.info { "Monolith delete variants ok: ${result.deleted} deleted shop=${shop.host}" }
      is DeleteVariantsResult.Error ->
        logMonolithFailure("deleteProductVariants", result.status, result.parsed, "shop=${shop.host}")
    }
  }

  private suspend fun handleOrderWebhook(
    shopify: ShopifyGraphqlService,
    bodyStr: String,
    topic: String,
    syncToMonolith: Boolean,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook order: could not parse Graphql id from body" }
      return
    }
    if (syncToMonolith) {
      syncShopifyOrderToMonolith(shopify, monolithService, id, topic)
      return
    }
    log.info { "Webhook $topic acknowledged id=$id (monolith sync disabled)" }
  }
}
