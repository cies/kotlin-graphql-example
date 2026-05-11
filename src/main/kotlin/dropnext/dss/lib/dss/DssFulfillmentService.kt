package dropnext.dss.lib.dss

import dropnext.dss.lib.dss.dto.Shipment
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateResponse
import com.example.graphql.generated.FulfillmentCancelMutation
import com.example.graphql.generated.FulfillmentCreateWithLineItems
import com.example.graphql.generated.FulfillmentEventCreateMutation
import com.example.graphql.generated.GetOrderForDss
import com.example.graphql.generated.getorderfordss.FulfillmentOrder
import com.example.graphql.generated.getorderfordss.Order
import com.example.graphql.generated.inputs.FulfillmentEventInput
import com.example.graphql.generated.inputs.FulfillmentOrderLineItemInput
import com.example.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import com.example.graphql.generated.inputs.FulfillmentTrackingInput
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.request.header
import kotlin.math.min

class DssFulfillmentService {
  /**
   * Cancels all existing open Shopify fulfillments for the order, then creates new fulfillments
   * from the provided shipments. Fulfillment orders are resolved automatically by matching
   * product_variant_id against fulfillment order line items.
   */
  suspend fun syncShipmentsWithFulfillments(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
    val orderGid = orderGid(payload.shopifyOrderId)

    // Load the order to get current fulfillments
    val orderBefore =
      loadOrder(graphQLClient, accessToken, orderGid)
        ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    // Cancel all existing non-cancelled fulfillments
    val existingFulfillmentGids =
      orderBefore.fulfillments
        .map { it.id.toString() }
        .filter { it.isNotBlank() }

    for (fulfillmentGid in existingFulfillmentGids) {
      val r =
        runCatching {
          graphQLClient.execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid))) {
            header("X-Shopify-Access-Token", accessToken)
          }
        }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
      val errs = r.data?.fulfillmentCancel?.userErrors.orEmpty()
      // Ignore "already cancelled" errors — they are no-ops
      val realErrs = errs.filter { !it.message.contains("already", ignoreCase = true) }
      if (realErrs.isNotEmpty()) {
        return FulfillmentResult.Err.UserError(realErrs.map { it.message })
      }
      if (!r.errors.isNullOrEmpty()) {
        return FulfillmentResult.Err.GraphQlError(r.errors.toString())
      }
    }

    // Reload order after cancellations so remaining quantities are accurate
    val orderAfter =
      if (existingFulfillmentGids.isEmpty()) {
        orderBefore
      } else {
        loadOrder(graphQLClient, accessToken, orderGid)
          ?: return FulfillmentResult.Err.NotFound("order not found after cancel")
      }

    val newIds = mutableListOf<Long>()
    for (shipment in payload.shipments) {
      val result = createFulfillmentForShipment(graphQLClient, accessToken, orderAfter, shipment)
      when (result) {
        is FulfillmentResult.Ok -> newIds.addAll(result.value)
        is FulfillmentResult.Err -> return result
      }
    }
    return FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = newIds))
  }

  /**
   * Looks up the Shopify fulfillment by order + tracking number, then creates a FulfillmentEvent.
   */
  suspend fun createTrackingEvent(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> {
    val parsedStatus = parseFulfillmentEventStatus(payload.status)
    val status =
      when (parsedStatus) {
        is ParsedFulfillmentStatus.Known -> parsedStatus.value
        is ParsedFulfillmentStatus.Unknown ->
          return FulfillmentResult.Err.UserError(listOf("unsupported tracking status: ${parsedStatus.raw}"))
      }

    val orderGid = orderGid(payload.shopifyOrderId)
    val order =
      loadOrder(graphQLClient, accessToken, orderGid)
        ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    // Find the fulfillment with the matching tracking number
    val fulfillmentGid =
      order.fulfillments
        .find { fulfillment ->
          fulfillment.trackingInfo.any { it.number == payload.trackingNumber }
        }
        ?.id
        ?.toString()
        ?: return FulfillmentResult.Err.NotFound(
          "no fulfillment with tracking number ${payload.trackingNumber} on order ${payload.shopifyOrderId}",
        )

    val input =
      FulfillmentEventInput(
        fulfillmentId = fulfillmentGid,
        happenedAt = payload.happenedAt,
        status = status,
        message = payload.message,
      )
    val r =
      runCatching {
        graphQLClient.execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input))) {
          header("X-Shopify-Access-Token", accessToken)
        }
      }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
    val errs = r.data?.fulfillmentEventCreate?.userErrors.orEmpty()
    if (errs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(errs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphQlError(r.errors.toString())
    }
    val ev = r.data?.fulfillmentEventCreate?.fulfillmentEvent
    val eid =
      legacyIdFromGid(ev?.id.toString())
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
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    order: Order,
    shipment: Shipment,
  ): FulfillmentResult<List<Long>> {
    // Group requested line items by fulfillment order
    val foGroups = mutableMapOf<FulfillmentOrder, MutableList<FulfillmentOrderLineItemInput>>()
    for (req in shipment.lineItems) {
      // Find which FO(s) contain this variant
      for (foe in order.fulfillmentOrders.edges) {
        val fo = foe.node
        val match =
          fo.lineItems.edges
            .map { it.node }
            .find { node ->
              node.variant?.legacyResourceId?.toString()?.toLongOrNull() == req.productVariantId
            }
        if (match != null) {
          val qty = min(req.quantity, match.remainingQuantity)
          if (qty > 0) {
            foGroups
              .getOrPut(fo) { mutableListOf() }
              .add(FulfillmentOrderLineItemInput(id = match.id, quantity = qty))
          }
          break
        }
      }
    }

    if (foGroups.isEmpty()) {
      return FulfillmentResult.Err.NotFound(
        "no matching open fulfillment order line items for shipment tracking=${shipment.trackingNumber}",
      )
    }

    val lineItemsByFo =
      foGroups.entries.map { (fo, inputs) ->
        FulfillmentOrderLineItemsInput(
          fulfillmentOrderId = fo.id,
          fulfillmentOrderLineItems = inputs,
        )
      }

    val tracking =
      FulfillmentTrackingInput(
        company = shipment.carrier,
        number = shipment.trackingNumber,
        url = shipment.trackingUrl,
      )

    val variables =
      FulfillmentCreateWithLineItems.Variables(
        lineItemsByFulfillmentOrder = lineItemsByFo,
        tracking = tracking,
        notifyCustomer = false,
      )

    val r =
      runCatching {
        graphQLClient.execute(FulfillmentCreateWithLineItems(variables)) {
          header("X-Shopify-Access-Token", accessToken)
        }
      }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }

    val createErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty()
    if (createErrs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(createErrs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphQlError(r.errors.toString())
    }

    val f = r.data?.fulfillmentCreate?.fulfillment
    val idNum = f?.legacyResourceId?.toString()?.toLongOrNull() ?: legacyIdFromGid(f?.id.toString())
    return FulfillmentResult.Ok(listOfNotNull(idNum))
  }

  private suspend fun loadOrder(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    orderGid: String,
  ): Order? {
    val r =
      runCatching {
        graphQLClient.execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))) {
          header("X-Shopify-Access-Token", accessToken)
        }
      }.getOrElse { return null }
    return r.data?.order
  }
}
