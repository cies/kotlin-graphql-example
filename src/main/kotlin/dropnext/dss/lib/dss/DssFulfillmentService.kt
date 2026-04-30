package dropnext.dss.lib.dss

import com.example.lib.dss.dto.SyncShipmentsWithFulfillmentsPayload
import com.example.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import com.example.lib.dss.dto.TrackingUpdatePayload
import com.example.lib.dss.dto.TrackingUpdateResponse
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
    val orderGid = orderGid(payload.shopifyOrderId)
    for (fid in payload.replaceFulfillmentIds) {
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
    for (nf in payload.newFulfillments) {
      val fo =
        findFulfillmentOrder(orderAfter, nf.fulfillmentOrderId)
          ?: return Result.failure(RuntimeException("fulfillment order ${nf.fulfillmentOrderId} not found"))
      val lineInputs =
        buildFoLineInputs(fo, nf.lineItems).getOrElse { return Result.failure(it) }
      val tracking =
        FulfillmentTrackingInput(
          company = nf.carrier,
          number = nf.trackingNumber,
          url = nf.trackingUrl,
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
    return Result.success(SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = newIds))
  }

  suspend fun createTrackingEvent(
    graphQLClient: GraphQLKtorClient,
    accessToken: String,
    payload: TrackingUpdatePayload,
  ): Result<TrackingUpdateResponse> {
    val parsedStatus = parseFulfillmentEventStatus(payload.status)
    val status =
      when (parsedStatus) {
        is ParsedFulfillmentStatus.Known -> parsedStatus.value
        is ParsedFulfillmentStatus.Unknown ->
          return Result.failure(RuntimeException("unsupported tracking status: ${parsedStatus.raw}"))
      }
    val input =
      FulfillmentEventInput(
        fulfillmentId = fulfillmentGid(payload.fulfillmentId),
        happenedAt = payload.happenedAt,
        status = status,
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
    return Result.success(TrackingUpdateResponse(fulfillmentEventId = eid))
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
    requested: List<com.example.lib.dss.dto.ShipmentLineItem>,
  ): Result<List<FulfillmentOrderLineItemInput>?> {
    if (requested.isEmpty()) return Result.success(null)
    val inputs = mutableListOf<FulfillmentOrderLineItemInput>()
    for (req in requested) {
      val match =
        fo.lineItems.edges
          .map { it.node }
          .find { node ->
            node.variant?.legacyResourceId?.toString()?.toLongOrNull() == req.productVariantId
          }
          ?: return Result.failure(
            RuntimeException("variant ${req.productVariantId} not found on fulfillment order"),
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
