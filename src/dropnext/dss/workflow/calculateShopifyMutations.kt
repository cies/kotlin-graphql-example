package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.shopify.graphql.fulfillment.DryRunResult
import dropnext.dss.lib.shopify.graphql.fulfillment.dryRunAllShipments
import dropnext.graphql.generated.getorderfordss.Order


/** Pure: only works on its input values with no side effects. */
fun calculateShopifyMutations(
  order: Order,
  shipments: List<Shipment>,
): Result<List<ShopifyMutation>, DetermineShopifyMutationsError> {
  when (val match = dryRunAllShipments(order, shipments)) {
    is DryRunResult.UserError ->
      return Failure(DetermineShopifyMutationsError.UserError(match.messages))
    is DryRunResult.Ok -> {
      val mutations = mutableListOf<ShopifyMutation>()
      order.fulfillments
        .map { it.id }
        .filter { it.isNotBlank() }
        .forEach { fulfillmentId ->
          mutations += ShopifyMutation.FulfillmentCancel(fulfillmentId = fulfillmentId)
        }
      match.perShipment.forEachIndexed { index, shipmentMatch ->
        if (shipmentMatch.groups.isEmpty()) return@forEachIndexed
        val shipment = shipments[index]
        val lineItems = shipmentMatch.groups.flatMap { (fulfillmentOrder, inputs) ->
          inputs.map { input ->
            FulfillmentOrderLineItem(
              fulfillmentOrderId = fulfillmentOrder.id,
              lineItemId = input.id,
              quantity = input.quantity,
            )
          }
        }
        mutations += ShopifyMutation.FulfillmentCreate(
          lineItems = lineItems,
          trackingNumber = shipment.trackingNumber,
          carrier = shipment.carrier,
          trackingUrl = shipment.trackingUrl,
          notifyCustomer = false,
        )
      }
      return Success(mutations)
    }
  }
}
