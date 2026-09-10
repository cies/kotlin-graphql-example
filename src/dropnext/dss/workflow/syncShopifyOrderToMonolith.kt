package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.CreateOrderOutcome
import dropnext.dss.lib.monolith.MonolithResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.mapper.OrderLineItemOmission
import dropnext.dss.mapper.mapOrderForMonolith

import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Loads the Shopify order snapshot via [shopify] and POSTs [CreateShopifyOrderRequest] to the monolith.
 * Returns `null` when the order cannot be loaded or has no variant-backed lines:
 * a webhook is answered `200` either way, so the caller only needs the log.
 */
suspend fun syncShopifyOrderToMonolith(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  orderGid: String,
  webhookTopic: String,
): MonolithResult<CreateOrderOutcome>? {
  val order = when (val loaded = shopify.orderForDss(orderGid)) {
    is Failure -> {
      log.error { "Webhook $webhookTopic: could not load order orderGid=$orderGid error=${loaded.reason.message}" }
      return null
    }
    is Success -> loaded.value
  }
  log.info { "Webhook order loaded id=${order.id} name=${order.name}" }
  val mapping = mapOrderForMonolith(shopify.shop.subdomainOnly, order)
  val req = mapping.request
  mapping.omittedLineItems.forEach { omitted ->
    val line = "Webhook $webhookTopic: omitted line item ${omitted.lineItemId} " +
      "reason=${omitted.reason.name.lowercase()} shopifyOrderId=${req.shopifyOrderId}"
    // A tip or a custom line is the normal shape of an order; the other reasons are Shopify data we did not expect.
    if (omitted.reason == OrderLineItemOmission.NO_VARIANT) log.info { line } else log.warn { line }
  }
  if (req.lineItems.isEmpty()) {

    log.warn {
      "Webhook $webhookTopic: skip monolith order sync — mapped line_items empty " +
        "(no variant-backed lines or no fulfillment_order_id — e.g. tips/custom-only order)"
    }
    return null
  }
  return postMappedOrderToMonolith(monolith, req, webhookTopic)
}

/** POSTs a mapped order and logs success/failure (testable without Shopify Graphql). */
suspend fun postMappedOrderToMonolith(
  monolith: MonolithService,
  req: CreateShopifyOrderRequest,
  webhookTopic: String,
): MonolithResult<CreateOrderOutcome> {
  val result = monolith.postCreateOrder(req)
  when (result) {
    is Success -> {
      val detail = if (result.value == CreateOrderOutcome.AlreadyExisted) " (order already existed — duplicate webhook)" else ""
      log.info {
        "Monolith create order accepted$detail topic=$webhookTopic " +
          "shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size}"
      }
    }

    is Failure -> logMonolithFailure(
      operation = "postCreateOrder",
      error = result.reason,
      extra = "topic=$webhookTopic shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size} " +
        "fulfillmentStatus=${req.fulfillmentStatus}",
    )
  }
  return result
}
