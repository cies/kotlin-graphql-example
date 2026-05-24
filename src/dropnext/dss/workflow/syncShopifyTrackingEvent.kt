package dropnext.dss.workflow

import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateResponse
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.ParsedFulfillmentStatus
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.inputs.FulfillmentEventInput


/**
 * Looks up the Shopify fulfillment by order + tracking number, then creates a FulfillmentEvent.
 * Composes [ShopifyGraphqlService.loadOrderForDss] and [ShopifyGraphqlService.createFulfillmentEvent].
 */
suspend fun syncShopifyTrackingEvent(
  shopify: ShopifyGraphqlService,
  payload: TrackingUpdateRequest,
): FulfillmentResult<TrackingUpdateResponse> {
  val status = when (val parsed = ParsedFulfillmentStatus.parseFulfillmentEventStatus(payload.status)) {
    is ParsedFulfillmentStatus.Known -> parsed.value
    is ParsedFulfillmentStatus.Unknown ->
      return FulfillmentResult.Err.UserError(listOf("unsupported tracking status: ${parsed.raw}"))
  }

  val orderGid = orderGid(payload.shopifyOrderId)
  val orderResult = runCatching { shopify.loadOrderForDss(orderGid) }
    .getOrElse { return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found") }
  val order = orderResult.data?.order
    ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

  val fulfillmentGid = order.fulfillments.find { fulfillment ->
    fulfillment.trackingInfo.any { it.number == payload.trackingNumber }
  }?.id
    ?: return FulfillmentResult.Err.NotFound(
      "no fulfillment with tracking number ${payload.trackingNumber} on order ${payload.shopifyOrderId}",
    )

  val input = FulfillmentEventInput(
    fulfillmentId = fulfillmentGid,
    happenedAt = payload.happenedAt,
    status = status,
    message = payload.message,
  )
  val r = runCatching { shopify.createFulfillmentEvent(input) }
    .getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
  val errs = r.data?.fulfillmentEventCreate?.userErrors.orEmpty()
  if (errs.isNotEmpty()) {
    return FulfillmentResult.Err.UserError(errs.map { it.message })
  }
  val eventErrors = r.errors
  if (!eventErrors.isNullOrEmpty()) {
    return FulfillmentResult.Err.GraphqlError(eventErrors.joinToString("; ") { it.message })
  }
  val ev = r.data?.fulfillmentEventCreate?.fulfillmentEvent
  val eid = ev?.id?.let { legacyIdFromGid(it) }
    ?: return FulfillmentResult.Err.NotFound("missing fulfillment event id")
  return FulfillmentResult.Ok(TrackingUpdateResponse(fulfillmentEventId = eid))
}
