package dropnext.dss.handler

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.ktor.toHttpStatus
import dropnext.dss.lib.logging.currentTraceId
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.lib.shopify.webhook.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.lib.shopify.webhook.productIdFromProductWebhook
import dropnext.dss.lib.shopify.webhook.shopDomainFromWebhook
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.WebhookSkipReason
import dropnext.dss.workflow.deleteShopifyProductFromMonolith
import dropnext.dss.workflow.isTransient
import dropnext.dss.workflow.syncShopifyOrderToMonolith
import dropnext.dss.workflow.syncShopifyProductToMonolith
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.time.Instant


private val log = KotlinLogging.logger {}

/**
 * The one inbound Shopify webhook endpoint: verifies the body signature, resolves the shop and its
 * service, and dispatches on the topic to the workflow that mirrors the change into the monolith.
 * The answer is chosen from the outcome: `200` for whatever a redelivery could not improve (done,
 * nothing to do, a token or a request that is refused), a `502` when Shopify or the monolith did not
 * answer or answered a `5xx`, so Shopify's own redelivery, with backoff for up to two days, is the retry.
 * Every verified delivery ends in one [WebhookDeliveryReport]: the summary log line and the response body.
 */
class ShopifyWebhookHandlers(
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
  private val monolithService: MonolithService,
  private val shopifyHmacVerifierService: ShopifyHmacVerifierService,
) {

  suspend fun handleShopifyWebhook(call: ApplicationCall) {
    val receivedAt = Instant.now()
    val startedNanos = System.nanoTime()
    val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
    val topic = ShopifyWebhookTopic.parse(call.request.headers["X-Shopify-Topic"])
    val shopDomainHeader = call.request.headers["X-Shopify-Shop-Domain"]
    val body = call.receive<ByteArray>()
    if (!shopifyHmacVerifierService.verifyWebhook(hmacHeader, body)) {
      // The one line an operator gets for a delivery that was never acted on: a rotated app secret,
      // a subscription left behind by another app, or a forged call all look the same from here.
      log.warn {
        "Webhook rejected: HMAC mismatch topic=${topic.raw} shopDomainHeader=$shopDomainHeader " +
          "hmacHeaderPresent=${hmacHeader != null} bodyBytes=${body.size}"
      }
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    val bodyString = body.decodeToString()
    log.info { "Webhook verified topic=${topic.raw} shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}" }

    val shop = shopDomainFromWebhook(shopDomainHeader, bodyString)
    val outcome = if (shop == null) {
      log.error { "Webhook: no shop domain topic=${topic.raw} shopDomainHeader=$shopDomainHeader" }
      WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_SHOP_DOMAIN)
    } else {
      mirror(topic, shop, bodyString)
    }

    val report = WebhookDeliveryReport(
      topic = topic.raw,
      shop = shop,
      webhookId = call.request.headers["X-Shopify-Webhook-Id"],
      lagMillis = call.request.headers["X-Shopify-Triggered-At"]?.let { lagMillis(it, receivedAt) },
      tookMillis = (System.nanoTime() - startedNanos) / 1_000_000,
      outcome = outcome,
    )
    val answer = if (outcome.isTransient) DssError.UpstreamFailure("not mirrored, please redeliver") else null
    val line = report.logLine(answeredStatus = answer?.toHttpStatus()?.value ?: HttpStatusCode.OK.value)
    when (report.logLevel) {
      WebhookDeliveryReport.LogLevel.INFO -> log.info { line }
      WebhookDeliveryReport.LogLevel.WARN -> log.warn { line }
      WebhookDeliveryReport.LogLevel.ERROR -> log.error { line }
    }
    if (answer != null) call.respondError(answer) else call.respond(HttpStatusCode.OK, report.toResponse(currentTraceId()))
  }

  // The Shopify service is resolved per branch: a delete needs none (the product is gone and the
  // body carries its id), and a shop without a token must not lose its deletes.
  private suspend fun mirror(topic: ShopifyWebhookTopic, shop: ShopDomain, bodyString: String): WebhookMirrorOutcome =
    when (topic) {
      ShopifyWebhookTopic.ProductsCreate, ShopifyWebhookTopic.ProductsUpdate -> {
        val shopify = shopifyServiceOrLog(shop, topic) ?: return skipped(WebhookSkipReason.NO_ADMIN_TOKEN)
        val gid = resourceGidOrLog(topic, bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        syncShopifyProductToMonolith(shopify, monolithService, gid)
      }

      ShopifyWebhookTopic.ProductsDelete -> {
        val productId = productIdOrLog(topic, bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        deleteShopifyProductFromMonolith(monolithService, shop, productId)
      }

      ShopifyWebhookTopic.OrdersCreate -> {
        val shopify = shopifyServiceOrLog(shop, topic) ?: return skipped(WebhookSkipReason.NO_ADMIN_TOKEN)
        val gid = resourceGidOrLog(topic, bodyString) ?: return skipped(WebhookSkipReason.NO_RESOURCE_ID)
        syncShopifyOrderToMonolith(shopify, monolithService, gid, topic.raw)
      }

      // The `create` already carried the order; an update is acknowledged, not mirrored.
      ShopifyWebhookTopic.OrdersUpdated -> skipped(WebhookSkipReason.TOPIC_NOT_MIRRORED)

      is ShopifyWebhookTopic.Other -> skipped(WebhookSkipReason.TOPIC_NOT_MIRRORED)
    }

  private fun skipped(reason: WebhookSkipReason) = WebhookMirrorOutcome.Skipped(reason)

  /** The shop's Graphql service, or `null` after the error line that is all an operator gets. */
  private suspend fun shopifyServiceOrLog(shop: ShopDomain, topic: ShopifyWebhookTopic): ShopifyGraphqlService? =
    shopifyGraphqlServiceFactory.forShop(shop)
      ?: run {
        log.error {
          "Webhook: no Admin token topic=${topic.raw} shop=$shop " +
            "(configure DSS_SHOP_ACCESS_TOKENS or complete the OAuth install)"
        }
        null
      }

  private fun resourceGidOrLog(topic: ShopifyWebhookTopic, bodyString: String): String? =
    graphqlResourceIdFromShopifyWebhook(topic.raw, bodyString)
      ?: run {
        log.warn { "Webhook ${topic.raw}: could not parse Graphql id from body" }
        null
      }

  private fun productIdOrLog(topic: ShopifyWebhookTopic, bodyString: String): ShopifyProductId? =
    productIdFromProductWebhook(bodyString)
      ?: run {
        log.warn { "Webhook ${topic.raw}: could not parse product id from body" }
        null
      }
}

/** Shopify sends `X-Shopify-Triggered-At` as ISO-8601; an unparseable value costs the field, not the delivery. */
private fun lagMillis(triggeredAt: String, receivedAt: Instant): Long? =
  runCatching { receivedAt.toEpochMilli() - Instant.parse(triggeredAt.trim()).toEpochMilli() }.getOrNull()
