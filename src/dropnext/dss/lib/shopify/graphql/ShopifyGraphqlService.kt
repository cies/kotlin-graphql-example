package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.types.GraphQLClientResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentCreateWithTracking
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import dropnext.graphql.generated.GetOrderById
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput


/**
 * Per-shop Shopify Admin API surface. Handlers and workflows program against this interface;
 * production wires [HttpShopifyGraphqlService] (real Graphql over HTTPS, per-shop access token);
 * tests wire [dropnext.dss.testing.fake.FakeShopifyGraphqlService] (in-memory stubs).
 *
 * Each instance is bound to a single [shop] — the per-shop access token is injected at
 * construction and never leaks back across the API.
 *
 * Methods here are **single-shot Graphql primitives**. Multi-step orchestrations
 * (cancel-then-recreate, scan-then-register) live as workflow functions under
 * `dropnext.dss.workflow.*` and compose these primitives.
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

  // ---------- fulfillment primitives (composed by workflow/syncShopifyShipments… and …TrackingEvent…) ----------

  /** `FulfillmentCancel` — cancels a single Shopify fulfillment by GID. */
  suspend fun cancelFulfillment(fulfillmentGid: String): GraphQLClientResponse<FulfillmentCancelMutation.Result>

  /** `FulfillmentCreateWithLineItems` — creates one fulfillment spanning one or more fulfillment orders. */
  suspend fun createFulfillmentWithLineItems(
    lineItemsByFulfillmentOrder: List<FulfillmentOrderLineItemsInput>,
    tracking: FulfillmentTrackingInput,
    notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithLineItems.Result>

  /** `FulfillmentEventCreate` — appends a tracking event to an existing fulfillment. */
  suspend fun createFulfillmentEvent(input: FulfillmentEventInput): GraphQLClientResponse<FulfillmentEventCreateMutation.Result>

  // ---------- webhook subscription primitives (composed by workflow/registerShopifyWebhooks) ----------

  /** `GetWebhookSubscriptions` — lists the shop's existing subscriptions filtered by [topics] / [callbackUrl]. */
  suspend fun getWebhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String,
  ): GraphQLClientResponse<GetWebhookSubscriptions.Result>

  /** `RegisterWebhook` — subscribes the shop to one topic at [callbackUrl] with optional projected fields. */
  suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): GraphQLClientResponse<RegisterWebhook.Result>
}
