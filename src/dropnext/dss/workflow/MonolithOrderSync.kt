package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.graphql.generated.GetOrderForDss
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import org.slf4j.MDC


private val log = KotlinLogging.logger {}

/**
 * Loads Shopify order snapshot and POSTs [CreateShopifyOrderRequest] to the monolith.
 * Returns null when the order cannot be loaded or has no variant-backed lines.
 */
suspend fun syncShopifyOrderToMonolith(
  graphQLClient: GraphQLKtorClient,
  token: String,
  shopMyShopifyHost: String,
  monolith: MonolithService,
  orderGid: String,
  webhookTopic: String,
): CreateOrderResult? {
  val result = graphQLClient.execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))) {
    header("X-Shopify-Access-Token", token)
  }
  val order = result.data?.order
  if (order == null) {
    log.error { "Webhook $webhookTopic: order null orderGid=$orderGid errors=${result.errors}" }
    return null
  }
  log.info { "Webhook order loaded id=${order.id} name=${order.name} errors=${result.errors}" }
  val variantBackedCount = order.lineItems.edges.count { it.node.variant != null }
  val req = orderToCreateShopifyOrderRequest(shopifySubdomainShort(shopMyShopifyHost), order)
  if (variantBackedCount > req.lineItems.size) {
    log.warn {
      "Webhook $webhookTopic: omitted ${variantBackedCount - req.lineItems.size} line item(s) " +
        "without resolvable fulfillment_order_id shopifyOrderId=${req.shopifyOrderId}"
    }
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

/** POSTs a mapped order and logs success/failure (testable without Shopify GraphQL). */
suspend fun postMappedOrderToMonolith(
  monolith: MonolithService,
  req: CreateShopifyOrderRequest,
  webhookTopic: String,
): CreateOrderResult {
  val result = monolith.postCreateOrder(req)
  when (result) {
    is CreateOrderResult.HttpResponseSummary -> {
      val detail =
        when (result.status) {
          409 -> " (order already existed — duplicate webhook)"
          else -> ""
        }
      log.info {
        "Monolith create order accepted$detail topic=$webhookTopic httpStatus=${result.status} " +
          "shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size}"
      }
    }

    is CreateOrderResult.Error -> {
      if (result.status >= 500) MDC.put("dss.webhook.outcome", "failed")
      try {
        logMonolithFailure(
          operation = "postCreateOrder",
          status = result.status,
          parsed = result.parsed,
          extra =
            "topic=$webhookTopic shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size} " +
              "fulfillmentStatus=${req.fulfillmentStatus}",
        )
      } finally {
        MDC.remove("dss.webhook.outcome")
      }
    }
  }
  return result
}
