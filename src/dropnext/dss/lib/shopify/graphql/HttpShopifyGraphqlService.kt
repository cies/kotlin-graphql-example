package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.graphql.generated.FulfillmentCancelMutation
import dropnext.graphql.generated.FulfillmentCreateWithLineItems
import dropnext.graphql.generated.FulfillmentEventCreateMutation
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetProductById
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.enums.FulfillmentEventStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException



/**
 * Production [ShopifyGraphqlService] — speaks real Graphql to a shop's Admin API endpoint over
 * the shared [GraphQLKtorClient], injecting the per-shop [accessToken] on every request.
 *
 * Instances are created by [HttpShopifyGraphqlServiceFactory].
 * Stateless aside from its three injected fields,
 * so a single instance is safe for concurrent use across requests targeting the same shop.
 */
class HttpShopifyGraphqlService(
  override val shop: ShopDomain,
  private val gqlClient: GraphQLKtorClient,
  private val accessToken: ShopifyAdminToken,
) : ShopifyGraphqlService {

  override suspend fun shopIdentity(): ShopifyResult<ShopIdentityInfo> =
    execute(ShopIdentity()).map { data ->
      ShopIdentityInfo(
        shopId = legacyIdFromGid(data.shop.id)?.let(::ShopifyShopId),
        domain = ShopDomain.parse(data.shop.myshopifyDomain) ?: shop,
      )
    }

  override suspend fun productSampleCount(first: Int): ShopifyResult<Int> =
    execute(SyncProductsPage(SyncProductsPage.Variables(first = first, after = null)))
      .map { it.products.edges.size }

  override suspend fun productById(productGid: String): ShopifyResult<ShopProduct?> =
    execute(GetProductById(GetProductById.Variables(productGid))).map { data ->
      data.product?.let { ShopProduct(product = it, shopCurrencyCode = data.shop.currencyCode.name) }
    }

  override suspend fun orderForDss(orderGid: String): ShopifyResult<Order> =
    execute(GetOrderForDss(GetOrderForDss.Variables(orderGid))).flatMap { data ->
      data.order?.let { Success(it) }
        ?: Failure(ShopifyError.NotFound("order ${legacyIdFromGid(orderGid) ?: orderGid} not found"))
    }

  override suspend fun cancelFulfillment(fulfillmentGid: String): ShopifyResult<Unit> =
    execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid))).flatMap { data ->
      // "already canceled" is the state we wanted; everything else Shopify refuses is a real failure.
      val realErrors = data.fulfillmentCancel?.userErrors.orEmpty()
        .map { it.message }
        .filter { !it.contains("already", ignoreCase = true) }
      if (realErrors.isEmpty()) Success(Unit) else Failure(ShopifyError.UserError(realErrors))
    }

  override suspend fun createFulfillment(
    lines: List<FulfillmentLine>,
    tracking: FulfillmentTracking,
    notifyCustomer: Boolean,
  ): ShopifyResult<ShopifyFulfillmentId> {
    val lineItemsByFulfillmentOrder = lines
      .groupBy { it.fulfillmentOrderId }
      .map { (fulfillmentOrderId, lineItems) ->
        FulfillmentOrderLineItemsInput(
          fulfillmentOrderId = fulfillmentOrderId,
          fulfillmentOrderLineItems = lineItems.map { FulfillmentOrderLineItemInput(id = it.lineItemId, quantity = it.quantity) },
        )
      }
    val request = FulfillmentCreateWithLineItems(
      FulfillmentCreateWithLineItems.Variables(
        lineItemsByFulfillmentOrder = lineItemsByFulfillmentOrder,
        tracking = FulfillmentTrackingInput(company = tracking.company, number = tracking.number, url = tracking.url),
        notifyCustomer = notifyCustomer,
      ),
    )
    return execute(request).flatMap { data ->
      val payload = data.fulfillmentCreate
      val userErrors = payload?.userErrors.orEmpty().map { it.message }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      val fulfillment = payload?.fulfillment
        ?: return@flatMap Failure(ShopifyError.UserError(listOf("fulfillment missing in response")))
      val id = fulfillment.legacyResourceId.toLongOrNull() ?: legacyIdFromGid(fulfillment.id)
        ?: return@flatMap Failure(ShopifyError.GraphqlError("unparseable fulfillment id ${fulfillment.id}"))
      Success(ShopifyFulfillmentId(id))
    }
  }

  override suspend fun createFulfillmentEvent(
    fulfillmentGid: String,
    status: FulfillmentEventStatus,
    happenedAt: String,
    message: String?,
  ): ShopifyResult<ShopifyFulfillmentEventId> {
    val input = FulfillmentEventInput(fulfillmentId = fulfillmentGid, happenedAt = happenedAt, status = status, message = message)
    return execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input))).flatMap { data ->
      val payload = data.fulfillmentEventCreate
      val userErrors = payload?.userErrors.orEmpty().map { it.message }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      // Shopify accepted the mutation, reported no user error, and still returned no event: that is
      // Shopify's answer being broken, not a resource we failed to find. The caller located the
      // fulfillment on the order moments ago. A `GraphqlError` becomes a 502 for the monolith, which
      // retries a 5xx and drops a 4xx for good; a `NotFound` would have told it the fulfillment does
      // not exist, which is false, and would have lost the tracking event.
      val id = payload?.fulfillmentEvent?.id?.let(::legacyIdFromGid)
        ?: return@flatMap Failure(ShopifyError.GraphqlError("fulfillment event missing in response"))


      Success(ShopifyFulfillmentEventId(id))
    }
  }

  override suspend fun webhookSubscriptions(
    topics: List<WebhookSubscriptionTopic>,
    callbackUrl: String,
  ): ShopifyResult<List<WebhookSubscriptionStatus>> =
    execute(GetWebhookSubscriptions(GetWebhookSubscriptions.Variables(topics, callbackUrl))).map { data ->
      data.webhookSubscriptions.nodes.map { WebhookSubscriptionStatus(id = it.id, topic = it.topic.name, uri = it.uri) }
    }

  override suspend fun registerWebhook(
    topic: WebhookSubscriptionTopic,
    callbackUrl: String,
    includeFields: List<String>?,
  ): ShopifyResult<String> =
    execute(RegisterWebhook(RegisterWebhook.Variables(topic, callbackUrl, includeFields))).flatMap { data ->
      val payload = data.webhookSubscriptionCreate
      val userErrors = payload?.userErrors.orEmpty().map { userError ->
        val field = userError.field.orEmpty().joinToString(",")
        if (field.isNotBlank()) "$field: ${userError.message}" else userError.message
      }
      if (userErrors.isNotEmpty()) return@flatMap Failure(ShopifyError.UserError(userErrors))
      payload?.webhookSubscription?.id?.let { Success(it) }
        ?: Failure(ShopifyError.GraphqlError("webhook subscription missing in response"))
    }

  /**
   * The single point that runs an operation: injects the per-shop `X-Shopify-Access-Token` header
   * and does the triage every caller used to repeat — a non-2xx status is [ShopifyError.TokenRejected]
   * or [ShopifyError.HttpError], a thrown transport or decoding failure is [ShopifyError.Network],
   * top-level `errors` are [ShopifyError.GraphqlError], and so is a response without `data`. The
   * named methods above only look at their payload.
   *
   * The status is caught as an exception because the Graphql client runs with `expectSuccess`. Its
   * message would carry the response body, so only the status survives.
   */
  private suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): ShopifyResult<T> {
    val response = try {
      gqlClient.execute(request) { header("X-Shopify-Access-Token", accessToken.value) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: ResponseException) {
      val status = e.response.status.value
      return Failure(
        if (e.response.status == HttpStatusCode.Unauthorized) ShopifyError.TokenRejected(status)
        else ShopifyError.HttpError(status),
      )
    } catch (e: Exception) {
      return Failure(ShopifyError.Network(e.message ?: "network error"))
    }

    val errors = response.errors
    if (!errors.isNullOrEmpty()) {
      return Failure(ShopifyError.GraphqlError(errors.joinToString("; ") { it.message }))
    }
    val data = response.data ?: return Failure(ShopifyError.GraphqlError("empty response"))
    return Success(data)
  }
}
