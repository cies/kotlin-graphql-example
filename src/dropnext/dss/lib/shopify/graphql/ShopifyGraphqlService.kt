package dropnext.dss.lib.shopify.graphql

import dev.forkhandles.result4k.Result
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getproductbyid.Product


/**
 * Per-shop Shopify Admin API surface. Handlers and workflows program against this interface;
 * production wires [HttpShopifyGraphqlService] (real Graphql over HTTPS, per-shop access token);
 * tests wire `FakeShopifyGraphqlService` (in-memory stubs).
 *
 * Each instance is bound to a single [shop] — the per-shop access token is injected at
 * construction and never leaks back across the API.
 *
 * Methods are **single-shot Graphql primitives**, one per `.graphql` file, and everyone answers a [ShopifyResult]:
 * the transport failure, the top-level Graphql errors, and a mutation payload's `userErrors`
 * are all triaged once, here, so no caller has to know the wire shape.
 * Multistep orchestrations (scan-then-register, load-then-create) live as workflow functions under
 * `dropnext.dss.workflow.*` and compose these primitives.
 */
interface ShopifyGraphqlService {

  /** The [ShopDomain] this service is bound to. */
  val shop: ShopDomain

  // ---------- single-shot reads ----------

  /** `ShopIdentity` — used post-OAuth to capture the canonical `*.myshopify.com` host and legacy shop id. */
  suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo>

  /** `SyncProductsPage` — how many products the first page of [first] holds; the install page shows it as a smoke test. */
  suspend fun productSampleCount(first: Int): ShopifyResult<Int>

  /** `GetProductById` — the product to mirror after a `products/create` or `products/update` webhook; a successful `null` means Shopify has no such product. */
  suspend fun productById(productGid: String): ShopifyResult<ShopProduct?>

  /** `GetOrderForDss` — the order snapshot the fulfillment workflows and the monolith order sync work from; [ShopifyError.NotFound] when Shopify has no such order. */
  suspend fun orderForDss(orderGid: String): ShopifyResult<Order>

  // ---------- fulfillment primitives (composed by the workflow functions) ----------

  /** `FulfillmentCancel` — cancels a single Shopify fulfillment by GID; an already-cancelled fulfillment is a success. */
  suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit>

  /** `FulfillmentCreateWithLineItems` — creates one fulfillment spanning one or more fulfillment orders and answers its id. */
  suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId>

  /** `FulfillmentEventCreate` — appends a tracking event to an existing fulfillment and answers the event's id. */
  suspend fun createFulfillmentEvent(
    fulfillmentGid: String,
    status: FulfillmentEventStatus,
    happenedAt: String,
    message: String?,
  ): ShopifyResult<ShopifyFulfillmentEventId>

  // ---------- webhook subscription primitives (composed by workflow/registerShopifyWebhooks) ----------

  /** `GetWebhookSubscriptions` — this app's subscriptions for [topics], at [callbackUrl] only when one is given. */
  suspend fun webhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String?,
  ): ShopifyResult<List<WebhookSubscriptionStatus>>

  /** `RegisterWebhook` — subscribes the shop to one topic at [callbackUrl] with optional projected fields; answers the subscription's GID. */
  suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<String>
}

/** What every [ShopifyGraphqlService] call returns. */
typealias ShopifyResult<T> = Result<T, ShopifyError>

/**
 * Why a Shopify call produced no answer.
 *
 * [UserError] and [NotFound] are Shopify refusing what we sent (a `400` / `404` for the caller);
 * [TokenRejected] is Shopify refusing *us* (a `401`, and no retry can help);
 * [GraphqlError], [HttpError] and [Network] are Shopify or the wire failing (a `502`).
 */
sealed interface ShopifyError {
  val message: String

  /** The request never got an answer: connection failure, timeout, unreadable response. */
  data class Network(override val message: String) : ShopifyError

  /**
   * Shopify answered `401`: the Admin token is no longer valid, which is what an uninstall looks like
   * from here. Kept apart from [HttpError] because it is the one failure a retry cannot fix and the
   * one an operator has to act on (reinstall the app), so it must never read as a network blip.
   */
  data class TokenRejected(val httpStatus: Int) : ShopifyError {
    override val message: String
      get() = "Shopify rejected the Admin token (HTTP $httpStatus): the app was uninstalled or the token revoked"
  }

  /**
   * Shopify answered an HTTP error instead of a Graphql envelope: `429` when throttled, `5xx` when
   * down, `423` for a locked shop. Only the status is kept: the body is Shopify's error page and
   * would otherwise end up in our logs.
   */
  data class HttpError(val httpStatus: Int) : ShopifyError {
    override val message: String get() = "Shopify answered HTTP $httpStatus"
  }


  /** Shopify answered with top-level `errors` (throttled, invalid query, …) or without the data asked for. */
  data class GraphqlError(override val message: String) : ShopifyError

  /** A mutation payload's `userErrors`: the business rules of the shop refused the mutation. */
  data class UserError(val messages: List<String>) : ShopifyError {
    override val message: String get() = messages.joinToString("; ")
  }

  /** The referenced resource does not exist on the shop. */
  data class NotFound(override val message: String) : ShopifyError
}

/** The shop's canonical host and numeric id, as Shopify reports them. */
data class ShopIdentityInfo(
  val shopId: ShopifyShopId?,
  val domain: ShopDomain,
)

/** A product together with the shop's currency, which the product payload itself does not carry. */
data class ShopProduct(
  val product: Product,
  val shopCurrencyCode: String,
)

/** One line of a fulfillment to create: [quantity] of a fulfillment-order line item. */
data class FulfillmentLine(
  val fulfillmentOrderId: String,
  val lineItemId: String,
  val quantity: Int,
)

data class FulfillmentTracking(
  val company: String?,
  val number: String,
  val url: String?,
)
