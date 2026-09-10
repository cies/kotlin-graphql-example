package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.FulfillmentTracking
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult


/** Applies [mutations] to Shopify in order, stopping at the first failure. Empty list when there is nothing to create. */
suspend fun effectShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  mutations: List<ShopifyMutation>,
): ShopifyResult<List<ShopifyFulfillmentId>> {
  val newFulfillmentIds = mutableListOf<ShopifyFulfillmentId>()
  mutations.forEach { mutation ->
    when (mutation) {
      is ShopifyMutation.FulfillmentCancel ->
        when (val cancelled = shopifyGqlService.cancelFulfillment(mutation.fulfillmentId)) {
          is Failure -> return cancelled
          is Success -> Unit
        }

      is ShopifyMutation.FulfillmentCreate -> {
        val tracking = FulfillmentTracking(
          company = mutation.carrier,
          number = mutation.trackingNumber,
          url = mutation.trackingUrl,
        )
        when (val created = shopifyGqlService.createFulfillment(mutation.lineItems, tracking, mutation.notifyCustomer)) {
          is Failure -> return created
          is Success -> newFulfillmentIds += created.value
        }
      }
    }
  }
  return Success(newFulfillmentIds)
}
