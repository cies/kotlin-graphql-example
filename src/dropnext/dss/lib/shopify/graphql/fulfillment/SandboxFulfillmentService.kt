package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain


/**
 * Sandbox [FulfillmentService] wired in when `ENABLE_TEST_HARNESS=true` and
 * `DSS_SANDBOX_FAKE_SHOPIFY=true` (see `DevConfig`). Returns canned IDs so the HTML test
 * harness can exercise the DSS REST endpoints without hitting a real Shopify shop.
 */
// TODO: make this a fake, and remove this from the prod codebase
class SandboxFulfillmentService : FulfillmentService {
  override suspend fun syncShipmentsWithFulfillments(
    shop: ShopDomain,
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> =
    FulfillmentResult.Ok(
      SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = listOf(SANDBOX_FULFILLMENT_ID)),
    )

  override suspend fun createTrackingEvent(
    shop: ShopDomain,
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> =
    FulfillmentResult.Ok(TrackingUpdateResponse(fulfillmentEventId = SANDBOX_FULFILLMENT_ID))

  companion object {
    /** Documented canned value — easy to grep for and rule out a real Shopify response. */
    const val SANDBOX_FULFILLMENT_ID = 9_000_000_000_000_001L
  }
}
