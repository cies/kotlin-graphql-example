package dropnext.dss.handler

import dropnext.dss.lib.shopify.graphql.GraphqlClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.monolith.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.monolith.tokenOrNull
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.UpsertVariantsResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.webhook.ShopifySignatures
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.lib.shopify.webhook.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.lib.shopify.webhook.shopDomainFromWebhookBody
import dropnext.dss.shopify.shopMyShopifyHostFromWebhook
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.dss.shopify.toProductVariantItems
import dropnext.dss.lib.shopify.webhook.variantLegacyIdsFromProductWebhook
import dropnext.dss.workflow.syncShopifyOrderToMonolith
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.graphql.generated.GetProductById
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond


private val log = KotlinLogging.logger {}

/** Handler for `POST` to [DssPaths.WEBHOOKS_SHOPIFY] — verifies HMAC and dispatches per topic. */
class ShopifyWebhookHandlers(
  private val dssConfig: DssAppConfig,
  private val gqlClientCache: GraphqlClientCache,
  private val httpMonolithClient: MonolithService?,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val config = dssConfig.shopify

  suspend fun handleShopifyWebhook(call: ApplicationCall) {
    val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
    val topic = ShopifyWebhookTopic.parse(call.request.headers["X-Shopify-Topic"])
    val shopDomainHeader = call.request.headers["X-Shopify-Shop-Domain"]
    val body = call.receive<ByteArray>()
    if (!ShopifySignatures.verifyWebhook(hmacHeader, config.appClientSecret, body)) {
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    val bodyStr = body.decodeToString()
    log.info { "Webhook verified topic=${topic.raw} shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}" }

    val shopNorm = shopMyShopifyHostFromWebhook(shopDomainHeader, shopDomainFromWebhookBody(bodyStr))
    val token = shopNorm?.let {
      shopifyAdminTokenWithMonolithFallback(it, shopTokens, httpMonolithClient).tokenOrNull
    }
    if (shopNorm == null || token == null) {
      log.error {
        "Webhook: no Admin token topic=${topic.raw} shopDomainHeader=$shopDomainHeader shopNorm=$shopNorm " +
          "(configure DSS_SHOP_ACCESS_TOKENS or OAuth env entry)"
      }
      call.respond(HttpStatusCode.OK)
      return
    }

    val gqlClient = gqlClientCache.forShop(shopNorm, config.apiVersion)
    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate ->
        handleProductUpsert(gqlClient, token, shopNorm, bodyStr, topic.raw)
      ShopifyWebhookTopic.ProductsDelete -> handleProductDelete(shopNorm, bodyStr)
      ShopifyWebhookTopic.OrdersCreate ->
        handleOrderWebhook(gqlClient, token, shopNorm, bodyStr, topic.raw, syncToMonolith = true)
      ShopifyWebhookTopic.OrdersUpdated ->
        handleOrderWebhook(gqlClient, token, shopNorm, bodyStr, topic.raw, syncToMonolith = dssConfig.webhook.syncOrderOnUpdated)
      is ShopifyWebhookTopic.Other -> log.info { "Webhook topic not handled: ${topic.raw}" }
    }
    call.respond(HttpStatusCode.OK)
  }

  private suspend fun handleProductUpsert(
    gqlClient: GraphQLKtorClient,
    token: String,
    shopNorm: String,
    bodyStr: String,
    topic: String,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook product: could not parse Graphql id from body" }
      return
    }
    val r = gqlClient.execute(GetProductById(GetProductById.Variables(id))) {
      header("X-Shopify-Access-Token", token)
    }
    val product = r.data?.product
    val title = product?.title
    log.info { "Webhook product synced id=$id title=$title errors=${r.errors}" }

    if (product != null && httpMonolithClient != null) {
      val subdomain = shopifySubdomainShort(shopNorm)
      val currencyCode = r.data?.shop?.currencyCode?.name ?: "USD"
      val variantItems = product.toProductVariantItems(currencyCode)
      if (variantItems.isNotEmpty()) {
        val req = UpsertProductVariantsRequest(
          shopifySubdomain = subdomain,
          productVariants = variantItems,
        )
        when (val result = httpMonolithClient.upsertProductVariants(req)) {
          is UpsertVariantsResult.Ok ->
            log.info { "Monolith upsert variants ok: ${result.upserted} upserted shop=$shopNorm" }
          is UpsertVariantsResult.Error -> {
            val msg = "Monolith upsert variants failed: status=${result.status} ${result.errorMessage}"
            if (result.status >= 500) log.error { msg } else log.warn { msg }
          }
        }
      }
    }
  }

  private suspend fun handleProductDelete(shopNorm: String, bodyStr: String) {
    val id = graphqlResourceIdFromShopifyWebhook("products/delete", bodyStr)
    log.info { "Webhook product deleted id=$id" }

    if (httpMonolithClient == null) return
    val variantIds = variantLegacyIdsFromProductWebhook(bodyStr)
    if (variantIds.isEmpty()) return

    val subdomain = shopifySubdomainShort(shopNorm)
    val req = DeleteProductVariantsRequest(
      shopifySubdomain = subdomain,
      productVariantIds = variantIds,
    )
    when (val result = httpMonolithClient.deleteProductVariants(req)) {
      is DeleteVariantsResult.Ok ->
        log.info { "Monolith delete variants ok: ${result.deleted} deleted shop=$shopNorm" }
      is DeleteVariantsResult.Error ->
        logMonolithFailure("deleteProductVariants", result.status, result.parsed, "shop=$shopNorm")
    }
  }

  private suspend fun handleOrderWebhook(
    gqlClient: GraphQLKtorClient,
    token: String,
    shopNorm: String,
    bodyStr: String,
    topic: String,
    syncToMonolith: Boolean,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook order: could not parse Graphql id from body" }
      return
    }
    if (syncToMonolith && httpMonolithClient != null) {
      syncShopifyOrderToMonolith(gqlClient, token, shopNorm, httpMonolithClient, id, topic)
      return
    }
    log.info { "Webhook $topic acknowledged id=$id (monolith sync disabled)" }
  }
}
