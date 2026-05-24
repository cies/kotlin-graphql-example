package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.monolith.ShopifyServiceFactory
import dropnext.dss.lib.shopify.ShopDomain


/**
 * Production [FulfillmentService]: resolves a per-shop [dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService]
 * via [factory] and delegates the request. Returns [FulfillmentResult.Err.MissingToken] when no
 * Admin token can be found for [shop] (cache miss + monolith miss + no explicit header token),
 * which maps to a 401 at the handler layer.
 */
class ShopifyFulfillmentService(
  private val factory: ShopifyServiceFactory,
) : FulfillmentService {
  override suspend fun syncShipmentsWithFulfillments(
    shop: ShopDomain,
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
    val shopify = factory.forShop(shop) ?: return FulfillmentResult.Err.MissingToken
    return shopify.syncShipmentsWithFulfillments(payload)
  }

  override suspend fun createTrackingEvent(
    shop: ShopDomain,
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> {
    val shopify = factory.forShop(shop) ?: return FulfillmentResult.Err.MissingToken
    return shopify.createTrackingEvent(payload)
  }
}
