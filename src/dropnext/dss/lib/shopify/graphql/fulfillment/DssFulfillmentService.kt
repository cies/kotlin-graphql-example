package dropnext.dss.lib.shopify.graphql.fulfillment

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.shopify.legacyIdFromGid
import dropnext.dss.shopify.orderGid
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.ktor.client.request.*


/**
 * Pure orchestration around the Shopify fulfillment Graphql mutations consumed by the DSS REST
 * endpoints (see `DssHttpHandlers.handleSyncShipments` / `handleTrackingUpdate`).
 *
 * Stateless: no fields, no I/O of its own — every call receives the [GraphQLKtorClient] and Admin
 * token so a single instance is safe to share across requests. Each operation returns a
 * [FulfillmentResult] (`Ok` or one of the `Err.*` variants) so the handler can map the failure to
 * the right HTTP status without catching exceptions.
 */
// TODO(cies): name this a proper service that is constructed ones with gqlClient and access token
object DssFulfillmentService {
  /**
   * Cancels all existing open Shopify fulfillments for the order, then creates new fulfillments
   * from the provided shipments. Fulfillment orders are resolved automatically by matching
   * product_variant_id against fulfillment order line items.
   */
  suspend fun syncShipmentsWithFulfillments(
    gqlClient: GraphQLKtorClient,
    accessToken: String,
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
    val orderGid = orderGid(payload.shopifyOrderId)

    // Load the order to get current fulfillments
    val orderBefore = loadOrder(gqlClient, accessToken, orderGid)
      ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    // Cancel all existing non-canceled fulfillments
    val existingFulfillmentGids = orderBefore.fulfillments
      .map { it.id }
      .filter { it.isNotBlank() }

    for (fulfillmentGid in existingFulfillmentGids) {
      val r = runCatching {
        gqlClient.execute(
          FulfillmentCancelMutation(
            FulfillmentCancelMutation.Variables(fulfillmentGid)
          )
        ) { header("X-Shopify-Access-Token", accessToken) }
      }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
      val errs = r.data?.fulfillmentCancel?.userErrors.orEmpty()
      // Ignore "already canceled" errors — they are no-ops
      val realErrs = errs.filter { !it.message.contains("already", ignoreCase = true) }
      if (realErrs.isNotEmpty()) {
        return FulfillmentResult.Err.UserError(realErrs.map { it.message })
      }
      if (!r.errors.isNullOrEmpty()) {
        return FulfillmentResult.Err.GraphqlError(r.errors.toString())
      }
    }

    // Reload order after cancellations so remaining quantities are accurate
    val orderAfter = if (existingFulfillmentGids.isEmpty()) {
      orderBefore
    } else {
      loadOrder(gqlClient, accessToken, orderGid)
        ?: return FulfillmentResult.Err.NotFound("order not found after cancel")
    }


    val newIds = payload.shipments.flatMap { shipment ->
      when (val result =
        createFulfillmentForShipment(gqlClient, accessToken, orderAfter, shipment)) {

        is FulfillmentResult.Ok -> result.value
        is FulfillmentResult.Err -> return result
      }
    }
    return FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(newIds))
  }

  /**
   * Looks up the Shopify fulfillment by order + tracking number, then creates a FulfillmentEvent.
   */
  suspend fun createTrackingEvent(
    gqlClient: GraphQLKtorClient,
    accessToken: String,
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> {
    val status = when (val parsedStatus =
      ParsedFulfillmentStatus.parseFulfillmentEventStatus(payload.status)) {
      is ParsedFulfillmentStatus.Known -> parsedStatus.value
      is ParsedFulfillmentStatus.Unknown ->
        return FulfillmentResult.Err.UserError(listOf("unsupported tracking status: ${parsedStatus.raw}"))
    }

    val orderGid = orderGid(payload.shopifyOrderId)
    val order = loadOrder(gqlClient, accessToken, orderGid)
      ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    // Find the fulfillment with the matching tracking number
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
    val r = runCatching {
      gqlClient.execute(
        FulfillmentEventCreateMutation(
          FulfillmentEventCreateMutation.Variables(
            input
          )
        )
      ) {
        header("X-Shopify-Access-Token", accessToken)
      }
    }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
    val errs = r.data?.fulfillmentEventCreate?.userErrors.orEmpty()
    if (errs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(errs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphqlError(r.errors.toString())
    }
    val ev = r.data?.fulfillmentEventCreate?.fulfillmentEvent
    val eid = legacyIdFromGid(ev?.id.toString())
      ?: return FulfillmentResult.Err.NotFound("missing fulfillment event id")
    return FulfillmentResult.Ok(TrackingUpdateResponse(fulfillmentEventId = eid))
  }

  /**
   * For a single shipment, groups its line items by fulfillment order and creates one Shopify
   * fulfillment covering all involved FOs.
   *
   * Returns a list of new Shopify fulfillment legacy IDs (one per FO group in practice Shopify
   * collapses them into a single fulfillment, but the API takes a list).
   */
  private suspend fun createFulfillmentForShipment(
    gqlClient: GraphQLKtorClient,
    accessToken: String,
    order: Order,
    shipment: Shipment,
  ): FulfillmentResult<List<Long>> {
    val matchResult = matchShipmentToFulfillmentOrders(order, shipment)
    val foGroups =
      when (matchResult) {
        is ShipmentMatchResult.Ok -> matchResult.groups
        is ShipmentMatchResult.NotFound -> return FulfillmentResult.Err.NotFound(matchResult.detail)
        is ShipmentMatchResult.UserError -> return FulfillmentResult.Err.UserError(matchResult.messages)
      }

    val lineItemsByFo = foGroups.entries.map { (fo, inputs) ->
      FulfillmentOrderLineItemsInput(
        fulfillmentOrderId = fo.id,
        fulfillmentOrderLineItems = inputs,
      )
    }

    val tracking = FulfillmentTrackingInput(
      company = shipment.carrier,
      number = shipment.trackingNumber,
      url = shipment.trackingUrl,
    )

    val variables = FulfillmentCreateWithLineItems.Variables(
      lineItemsByFulfillmentOrder = lineItemsByFo,
      tracking = tracking,
      notifyCustomer = false,
    )

    val r = runCatching {
      gqlClient.execute(FulfillmentCreateWithLineItems(variables)) {
        header("X-Shopify-Access-Token", accessToken)
      }
    }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }

    val createErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty()
    if (createErrs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(createErrs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphqlError(r.errors.toString())
    }

    val f = r.data?.fulfillmentCreate?.fulfillment
    val idNum = f?.legacyResourceId?.toLongOrNull() ?: f?.id?.let { legacyIdFromGid(it) }
    return FulfillmentResult.Ok(listOfNotNull(idNum))
  }

  private suspend fun loadOrder(
    gqlClient: GraphQLKtorClient,
    accessToken: String,
    orderGid: String,
  ): Order? {
    val r = runCatching {
      gqlClient.execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))) {
        header("X-Shopify-Access-Token", accessToken)
      }
    }.getOrElse { return null }
    return r.data?.order
  }
}
