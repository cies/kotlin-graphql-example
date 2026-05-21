package dropnext.dss.lib.dss

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.graphql.generated.GetOrderForDss
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.request.header
import org.slf4j.Logger

/**
 * Loads Shopify order snapshot and POSTs [CreateShopifyOrderRequest] to the monolith.
 * Returns null when the order cannot be loaded or has no variant-backed lines.
 */
suspend fun syncShopifyOrderToMonolith(
  log: Logger,
  graphQLClient: GraphQLKtorClient,
  token: String,
  shopMyshopifyHost: String,
  monolith: MonolithService,
  orderGid: String,
  webhookTopic: String,
): CreateOrderResult? {
  val r =
    graphQLClient.execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))) {
      header("X-Shopify-Access-Token", token)
    }
  val order = r.data?.order
  if (order == null) {
    log.error("Webhook $webhookTopic: order null orderGid=$orderGid errors=${r.errors}")
    return null
  }
  log.info("Webhook order loaded id=${order.id} name=${order.name} errors=${r.errors}")
  val variantBackedCount = order.lineItems.edges.count { it.node.variant != null }
  val req = orderToCreateShopifyOrderRequest(shopifySubdomainShort(shopMyshopifyHost), order)
  if (variantBackedCount > req.lineItems.size) {
    log.warn(
      "Webhook $webhookTopic: omitted ${variantBackedCount - req.lineItems.size} line item(s) " +
        "without resolvable fulfillment_order_id shopifyOrderId=${req.shopifyOrderId}",
    )
  }
  if (req.lineItems.isEmpty()) {
    log.warn(
      "Webhook $webhookTopic: skip monolith order sync — mapped line_items empty " +
        "(no variant-backed lines or no fulfillment_order_id — e.g. tips/custom-only order)",
    )
    return null
  }
  return postMappedOrderToMonolith(log, monolith, req, webhookTopic)
}

/** POSTs a mapped order and logs success/failure (testable without Shopify GraphQL). */
suspend fun postMappedOrderToMonolith(
  log: Logger,
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
      log.info(
        "Monolith create order accepted$detail topic=$webhookTopic httpStatus=${result.status} " +
          "shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size}",
      )
    }
    is CreateOrderResult.Error -> {
      if (result.status >= 500) org.slf4j.MDC.put("dss.webhook.outcome", "failed")
      try {
        logMonolithFailure(
          log,
          operation = "postCreateOrder",
          status = result.status,
          parsed = result.parsed,
          extra =
            "topic=$webhookTopic shopifyOrderId=${req.shopifyOrderId} lines=${req.lineItems.size} " +
              "fulfillmentStatus=${req.fulfillmentStatus}",
        )
      } finally {
        org.slf4j.MDC.remove("dss.webhook.outcome")
      }
    }
  }
  return result
}
