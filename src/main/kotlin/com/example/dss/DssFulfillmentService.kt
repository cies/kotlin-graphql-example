package com.example.dss

import com.example.dss.dto.SyncShipmentsWithFulfillmentsPayload
import com.example.dss.dto.SyncShipmentsWithFulfillmentsResponse
import com.example.dss.dto.TrackingUpdatePayload
import com.example.dss.dto.TrackingUpdateResponse
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
  suspend fun syncShipmentsWithFulfillments(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    payload: SyncShipmentsWithFulfillmentsPayload,
  ): Result<SyncShipmentsWithFulfillmentsResponse> {
    val orderGid = orderGid(payload.shopify_order_id)
    for (fid in payload.replace_fulfillment_ids) {
      val r =
        graphQLClient.execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid(fid)))) {
          header("X-Shopify-Access-Token", accessToken)
        }
      val errs = r.data?.fulfillmentCancel?.userErrors.orEmpty()
      if (errs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        return Result.failure(
          RuntimeException("fulfillmentCancel failed: ${errs.joinToString { it.message }} graphql=${r.errors}"),
        )
      }
    }
    val orderAfter =
      loadOrder(graphQLClient, accessToken, orderGid)
        ?: return Result.failure(RuntimeException("order not found after cancel"))
    val newIds = mutableListOf<Long>()
    for (nf in payload.new_fulfillments) {
      val fo =
        findFulfillmentOrder(orderAfter, nf.fulfillment_order_id)
          ?: return Result.failure(RuntimeException("fulfillment order ${nf.fulfillment_order_id} not found"))
      val lineInputs =
        buildFoLineInputs(fo, nf.line_items).getOrElse { return Result.failure(it) }
      val tracking =
        FulfillmentTrackingInput(
          company = nf.carrier,
          number = nf.tracking_number,
          url = nf.tracking_url,
        )
      val variables =
        FulfillmentCreateWithLineItems.Variables(
          lineItemsByFulfillmentOrder =
            listOf(
              FulfillmentOrderLineItemsInput(
                fulfillmentOrderId = fo.id,
                fulfillmentOrderLineItems = lineInputs,
              ),
            ),
          tracking = tracking,
          notifyCustomer = false,
        )
      val r =
        graphQLClient.execute(FulfillmentCreateWithLineItems(variables)) {
          header("X-Shopify-Access-Token", accessToken)
        }
      val createErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty()
      if (createErrs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        return Result.failure(
          RuntimeException("fulfillmentCreate failed: ${createErrs.joinToString { it.message }} graphql=${r.errors}"),
        )
      }
      val f = r.data?.fulfillmentCreate?.fulfillment
      val leg = f?.legacyResourceId
      val idNum =
        leg?.toString()?.toLongOrNull() ?: legacyIdFromGid(f?.id.toString())
      if (idNum != null) newIds.add(idNum)
    }
    return Result.success(SyncShipmentsWithFulfillmentsResponse(new_fulfillment_ids = newIds))
  }

  suspend fun createTrackingEvent(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    payload: TrackingUpdatePayload,
  ): Result<TrackingUpdateResponse> {
    val input =
      FulfillmentEventInput(
        fulfillmentId = fulfillmentGid(payload.fulfillment_id),
        happenedAt = payload.happened_at,
        status = parseFulfillmentEventStatus(payload.status),
        message = payload.message,
      )
    val r =
      graphQLClient.execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input))) {
        header("X-Shopify-Access-Token", accessToken)
      }
    val errs = r.data?.fulfillmentEventCreate?.userErrors.orEmpty()
    if (errs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
      return Result.failure(
        RuntimeException("fulfillmentEventCreate failed: ${errs.joinToString { it.message }} graphql=${r.errors}"),
      )
    }
    val ev = r.data?.fulfillmentEventCreate?.fulfillmentEvent
    val eid =
      legacyIdFromGid(ev?.id.toString())
        ?: return Result.failure(RuntimeException("missing fulfillment event id"))
    return Result.success(TrackingUpdateResponse(fulfillment_event_id = eid))
  }

  private suspend fun loadOrder(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    orderGid: String,
  ): Order? {
    val r =
      graphQLClient.execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))) {
        header("X-Shopify-Access-Token", accessToken)
      }
    return r.data?.order
  }

  private fun findFulfillmentOrder(
    order: Order,
    fulfillmentOrderLegacyId: Long,
  ): FulfillmentOrder? =
    order.fulfillmentOrders.edges
      .map { it.node }
      .find { legacyIdFromGid(it.id.toString()) == fulfillmentOrderLegacyId }

  private fun buildFoLineInputs(
    fo: FulfillmentOrder,
    requested: List<com.example.dss.dto.ShipmentLineItem>,
  ): Result<List<FulfillmentOrderLineItemInput>?> {
    if (requested.isEmpty()) return Result.success(null)
    val inputs = mutableListOf<FulfillmentOrderLineItemInput>()
    for (req in requested) {
      val match =
        fo.lineItems.edges
          .map { it.node }
          .find { node ->
            node.variant?.legacyResourceId?.toString()?.toLongOrNull() == req.product_variant_id
          }
          ?: return Result.failure(
            RuntimeException("variant ${req.product_variant_id} not found on fulfillment order"),
          )
      val qty = min(req.quantity, match.remainingQuantity)
      if (qty <= 0) continue
      inputs.add(
        FulfillmentOrderLineItemInput(
          id = match.id,
          quantity = qty,
        ),
      )
    }
    return Result.success(inputs.ifEmpty { null })
  }
}
