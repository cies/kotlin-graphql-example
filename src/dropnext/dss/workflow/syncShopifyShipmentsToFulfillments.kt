package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.SyncShipmentsRunStats
import dropnext.dss.lib.shopify.graphql.fulfillment.formatSyncShipmentsLogLine
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Creates Shopify fulfillments from the supplied shipments without canceling existing ones.
 * Fulfillment orders are resolved automatically by matching `product_variant_id` against
 * fulfillment order line items and live remaining quantity.
 *
 * Composes [determineShopifyMutations] (read-only) and [effectShopifyMutations] (creates).
 */
suspend fun syncShopifyShipmentsToFulfillments(
  shopifyGqlService: ShopifyGraphqlService,
  payload: SyncShipmentsWithFulfillmentsRequest,
): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
  val determined = determineShopifyMutations(
    shopifyGqlService = shopifyGqlService,
    shopifyOrderId = payload.shopifyOrderId,
    shipments = payload.shipments,
  )
  val mutations = when (determined) {
    is Failure -> return determined.reason.toFulfillmentResult()
    is Success -> determined.value
  }

  when (val effected = effectShopifyMutations(shopifyGqlService, mutations)) {
    is Failure -> return effected.reason.toFulfillmentResult()
    is Success -> {
      val stats = SyncShipmentsRunStats(
        canceledCount = mutations.count { it is ShopifyMutation.FulfillmentCancel },
        createdCount = effected.value.size,
        skippedLines = 0,
        skippedShipments = payload.shipments.size - mutations.count { it is ShopifyMutation.FulfillmentCreate },
      )
      log.info {
        formatSyncShipmentsLogLine(
          shopifyGqlService.shop.subdomainOnly,
          payload.shopifyOrderId,
          stats,
          effected.value,
        )
      }
      return FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(effected.value))
    }
  }
}

private fun DetermineShopifyMutationsError.toFulfillmentResult(): FulfillmentResult<Nothing> =
  when (this) {
    is DetermineShopifyMutationsError.NotFound -> FulfillmentResult.Err.NotFound(detail)
    is DetermineShopifyMutationsError.UserError -> FulfillmentResult.Err.UserError(messages)
    is DetermineShopifyMutationsError.GraphqlError -> FulfillmentResult.Err.GraphqlError(raw)
    is DetermineShopifyMutationsError.Network -> FulfillmentResult.Err.Network(message)
  }

private fun ShopifyError.toFulfillmentResult(): FulfillmentResult<Nothing> =
  when (this) {
    is ShopifyError.NotFound -> FulfillmentResult.Err.NotFound(detail)
    is ShopifyError.UserError -> FulfillmentResult.Err.UserError(messages)
    is ShopifyError.GraphqlError -> FulfillmentResult.Err.GraphqlError(raw)
    is ShopifyError.Network -> FulfillmentResult.Err.Network(message)
  }
