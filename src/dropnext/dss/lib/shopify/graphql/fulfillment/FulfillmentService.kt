package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain


/**
 * Inbound abstraction for the DSS fulfillment REST endpoints. Lets the production binary swap
 * the real [dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService]-backed implementation for [SandboxFulfillmentService]
 * when `DSS_SANDBOX_FAKE_SHOPIFY=true`, without putting an `if (sandboxFakeShopify)` branch in
 * every handler.
 *
 * The handler-facing surface intentionally omits the
 * [com.expediagroup.graphql.client.ktor.GraphQLKtorClient] and Admin access token: the factory
 * inside [ShopifyFulfillmentService] resolves the token per call (cache → monolith fallback).
 * Callers cannot inject a token — the DSS internal routes are gated by `X-DSS-Internal-Secret`
 * and the monolith pushes fresh tokens via `PUT /stores/api-key`.
 */
interface FulfillmentService {
  /** Implements the [dropnext.dss.path.DssPaths.SYNC_SHIPMENTS_WITH_FULFILLMENTS] contract for [shop]. */
  suspend fun syncShipmentsWithFulfillments(
    shop: ShopDomain,
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse>

  /** Implements the [dropnext.dss.path.DssPaths.TRACKING_UPDATE] contract for [shop]. */
  suspend fun createTrackingEvent(
    shop: ShopDomain,
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse>
}
