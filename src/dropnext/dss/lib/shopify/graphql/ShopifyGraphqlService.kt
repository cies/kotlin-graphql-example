package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.types.GraphQLClientResponse
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookRegistrationReport
import dropnext.graphql.generated.FulfillmentCreateWithTracking
import dropnext.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import dropnext.graphql.generated.GetOrderById
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage


/**
 * Per-shop Shopify Admin API surface. Handlers and workflows program against this interface;
 * production wires [HttpShopifyGraphqlService] (real Graphql over HTTPS, per-shop access token);
 * tests wire [dropnext.dss.testing.fake.FakeShopifyGraphqlService] (in-memory stubs).
 *
 * Each instance is bound to a single [shop] — the per-shop access token is injected at
 * construction and never leaks back across the API.
 */
interface ShopifyGraphqlService {

  /** The [ShopDomain] this service is bound to. */
  val shop: ShopDomain

  // ---------- single-shot reads ----------

  /** `ShopIdentity` — used post-OAuth to capture the canonical `*.myshopify.com` host and legacy shop id. */
  suspend fun shopIdentity(): GraphQLClientResponse<ShopIdentity.Result>

  /** `SyncProductsPage` — paginated product list (used by the OAuth confirmation page and the demo route). */
  suspend fun syncProductsPage(
    first: Int,
    after: String? = null,
  ): GraphQLClientResponse<SyncProductsPage.Result>

  /** `GetProductById` — used by `ShopifyWebhookHandlers` to hydrate a product after a `products/create` or `products/update` webhook. */
  suspend fun getProductById(productGid: String): GraphQLClientResponse<GetProductById.Result>

  /** `GetOrderById` — demo-only lightweight order lookup; production order sync uses [loadOrderForDss]. */
  suspend fun getOrderById(orderGid: String): GraphQLClientResponse<GetOrderById.Result>

  /**
   * `GetOrderForDss` — the order snapshot the DSS forwards to the monolith on `orders/create`
   * and `orders/updated` webhooks. Returns the full response so callers can surface `errors`
   * alongside the data.
   */
  suspend fun loadOrderForDss(orderGid: String): GraphQLClientResponse<GetOrderForDss.Result>

  // ---------- demo mutations ----------

  /** `FulfillmentCreateWithTracking` — single-mutation create used by the `/demo/` route. */
  suspend fun demoCreateFulfillmentWithTracking(
    fulfillmentOrderId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithTracking.Result>

  /** `FulfillmentTrackingInfoUpdate` — single-mutation update used by the `/demo/` route. */
  suspend fun demoUpdateFulfillmentTracking(
    fulfillmentId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean?,
  ): GraphQLClientResponse<FulfillmentTrackingInfoUpdateMutation.Result>

  // ---------- multi-step orchestrations ----------

  /**
   * Cancels every open Shopify fulfillment on the order, then creates new fulfillments from the
   * provided shipments. Fulfillment orders are resolved automatically by matching
   * `product_variant_id` against fulfillment order line items.
   */
  suspend fun syncShipmentsWithFulfillments(
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse>

  /** Looks up the Shopify fulfillment by order + tracking number, then creates a FulfillmentEvent. */
  suspend fun createTrackingEvent(
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse>

  /**
   * Subscribes the shop to the five product/order topics DSS cares about. Orders subscriptions
   * are restricted to id-only fields so Shopify does not require "protected customer data"
   * approval (the DSS fetches the full order via Graphql after the webhook arrives).
   */
  suspend fun registerStandardWebhooks(callbackUrl: String): WebhookRegistrationReport
}
