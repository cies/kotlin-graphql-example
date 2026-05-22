package dropnext.dss.handler

import dropnext.dss.GraphQLClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.DssPaths
import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.dss.ShopifyAdminToken
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.dss.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.UpsertVariantsResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.ShopifySignatures
import dropnext.dss.shopify.ShopifyWebhookTopic
import dropnext.dss.shopify.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.shopify.shopDomainFromWebhookBody
import dropnext.dss.shopify.shopMyshopifyHostFromWebhook
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.dss.shopify.toProductVariantItems
import dropnext.dss.shopify.variantLegacyIdsFromProductWebhook
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
class WebhookHandlers(
  private val dssConfig: DssAppConfig,
  private val gqlClientCache: GraphQLClientCache,
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

    val shopNorm = shopMyshopifyHostFromWebhook(shopDomainHeader, shopDomainFromWebhookBody(bodyStr))
    val token = if (shopNorm != null) {
      when (val t = shopifyAdminTokenWithMonolithFallback(shopNorm, shopTokens, httpMonolithClient)) {
        ShopifyAdminToken.Missing -> null
        is ShopifyAdminToken.Resolved -> t.token
      }
    } else {
      null
    }
    if (shopNorm == null || token == null) {
      log.error {
        "Webhook: no Admin token topic=${topic.raw} shopDomainHeader=$shopDomainHeader shopNorm=$shopNorm " +
          "(configure DSS_SHOP_ACCESS_TOKENS or OAuth env entry)"
      }
      call.respond(HttpStatusCode.OK)
      return
    }

    val graphQLClient = gqlClientCache.forShop(shopNorm, config.apiVersion)
    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate ->
        handleProductUpsert(graphQLClient, token, shopNorm, bodyStr, topic.raw)
      ShopifyWebhookTopic.ProductsDelete -> handleProductDelete(shopNorm, bodyStr)
      ShopifyWebhookTopic.OrdersCreate ->
        handleOrderWebhook(graphQLClient, token, shopNorm, bodyStr, topic.raw, syncToMonolith = true)
      ShopifyWebhookTopic.OrdersUpdated ->
        handleOrderWebhook(graphQLClient, token, shopNorm, bodyStr, topic.raw, syncToMonolith = dssConfig.syncOrderOnUpdated)
      is ShopifyWebhookTopic.Other -> log.info { "Webhook topic not handled: ${topic.raw}" }
    }
    call.respond(HttpStatusCode.OK)
  }

  private suspend fun handleProductUpsert(
    graphQLClient: GraphQLKtorClient,
    token: String,
    shopNorm: String,
    bodyStr: String,
    topic: String,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook product: could not parse GraphQL id from body" }
      return
    }
    val r = graphQLClient.execute(GetProductById(GetProductById.Variables(id))) {
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
    graphQLClient: GraphQLKtorClient,
    token: String,
    shopNorm: String,
    bodyStr: String,
    topic: String,
    syncToMonolith: Boolean,
  ) {
    val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
    if (id == null) {
      log.warn { "Webhook order: could not parse GraphQL id from body" }
      return
    }
    if (syncToMonolith && httpMonolithClient != null) {
      syncShopifyOrderToMonolith(graphQLClient, token, shopNorm, httpMonolithClient, id, topic)
      return
    }
    log.info { "Webhook $topic acknowledged id=$id (monolith sync disabled)" }
  }
}
