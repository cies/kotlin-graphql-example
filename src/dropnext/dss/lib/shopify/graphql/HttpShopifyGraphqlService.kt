package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
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
import io.ktor.client.request.header


/**
 * Production [ShopifyGraphqlService] - speaks real Graphql to a shop's Admin API endpoint over
 * the shared [GraphQLKtorClient], injecting the per-shop [accessToken] on every request.
 *
 * Instances are created by [dropnext.dss.lib.monolith.HttpShopifyGraphqlServiceFactory]; the OAuth
 * callback constructs one ad-hoc with the just-issued token before it has been cached.
 *
 * Stateless aside from its three injected fields, so a single instance is safe for concurrent
 * use across requests targeting the same shop.
 */
class HttpShopifyGraphqlService(
  override val shop: ShopDomain,
  private val gqlClient: GraphQLKtorClient,
  private val accessToken: String,
) : ShopifyGraphqlService {

  override suspend fun shopIdentity(): GraphQLClientResponse<ShopIdentity.Result> =
    execute(ShopIdentity())

  override suspend fun syncProductsPage(
    first: Int,
    after: String?,
  ): GraphQLClientResponse<SyncProductsPage.Result> =
    execute(SyncProductsPage(SyncProductsPage.Variables(first = first, after = after)))

  override suspend fun getProductById(productGid: String): GraphQLClientResponse<GetProductById.Result> =
    execute(GetProductById(GetProductById.Variables(productGid)))

  override suspend fun loadOrderForDss(orderGid: String): GraphQLClientResponse<GetOrderForDss.Result> =
    execute(GetOrderForDss(GetOrderForDss.Variables(orderGid)))

  override suspend fun cancelFulfillment(
    fulfillmentGid: String,
  ): GraphQLClientResponse<FulfillmentCancelMutation.Result> =
    execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid)))

  override suspend fun createFulfillmentWithLineItems(
    lineItemsByFulfillmentOrder: List<FulfillmentOrderLineItemsInput>,
    tracking: FulfillmentTrackingInput,
    notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithLineItems.Result> =
    execute(
      FulfillmentCreateWithLineItems(
        FulfillmentCreateWithLineItems.Variables(
          lineItemsByFulfillmentOrder = lineItemsByFulfillmentOrder,
          tracking = tracking,
          notifyCustomer = notifyCustomer,
        ),
      ),
    )

  override suspend fun createFulfillmentEvent(
    input: FulfillmentEventInput,
  ): GraphQLClientResponse<FulfillmentEventCreateMutation.Result> =
    execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input)))

  override suspend fun getWebhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String,
  ): GraphQLClientResponse<GetWebhookSubscriptions.Result> =
    execute(GetWebhookSubscriptions(GetWebhookSubscriptions.Variables(topics, callbackUrl)))

  override suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): GraphQLClientResponse<RegisterWebhook.Result> =
    execute(RegisterWebhook(RegisterWebhook.Variables(topic, callbackUrl, includeFields)))

  /**
   * The single point that injects the per-shop `X-Shopify-Access-Token` header. Every named
   * method above goes through this; no code outside this class constructs a Graphql request
   * (the Konsist `forbid dropnext.graphql.generated outside lib/shopify` rule enforces it).
   */
  private suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): GraphQLClientResponse<T> =
    gqlClient.execute(request) { header("X-Shopify-Access-Token", accessToken) }
}
