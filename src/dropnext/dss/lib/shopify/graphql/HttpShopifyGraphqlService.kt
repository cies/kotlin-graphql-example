package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import dropnext.dss.lib.dto.Shipment
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dto.TrackingUpdateRequest
import dropnext.dss.lib.dto.TrackingUpdateResponse
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.ParsedFulfillmentStatus
import dropnext.dss.lib.shopify.graphql.fulfillment.ShipmentMatchResult
import dropnext.dss.lib.shopify.graphql.fulfillment.matchShipmentToFulfillmentOrders
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookRegistrationReport
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
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
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentEventInput
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemsInput
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header


private val log = KotlinLogging.logger {}


/**
 * Production [ShopifyGraphqlService] — speaks real Graphql to a shop's Admin API endpoint over
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

  override suspend fun getOrderById(orderGid: String): GraphQLClientResponse<GetOrderById.Result> =
    execute(GetOrderById(GetOrderById.Variables(orderGid)))

  override suspend fun loadOrderForDss(orderGid: String): GraphQLClientResponse<GetOrderForDss.Result> =
    execute(GetOrderForDss(GetOrderForDss.Variables(orderGid)))

  override suspend fun demoCreateFulfillmentWithTracking(
    fulfillmentOrderId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean,
  ): GraphQLClientResponse<FulfillmentCreateWithTracking.Result> =
    execute(
      FulfillmentCreateWithTracking(
        FulfillmentCreateWithTracking.Variables(
          fulfillmentOrderId = fulfillmentOrderId,
          tracking = FulfillmentTrackingInput(company = company, number = trackingNumber, url = trackingUrl),
          notifyCustomer = notifyCustomer,
        ),
      ),
    )

  override suspend fun demoUpdateFulfillmentTracking(
    fulfillmentId: String,
    company: String?,
    trackingNumber: String?,
    trackingUrl: String?,
    notifyCustomer: Boolean?,
  ): GraphQLClientResponse<FulfillmentTrackingInfoUpdateMutation.Result> =
    execute(
      FulfillmentTrackingInfoUpdateMutation(
        FulfillmentTrackingInfoUpdateMutation.Variables(
          fulfillmentId = fulfillmentId,
          trackingInfoInput = FulfillmentTrackingInput(company = company, number = trackingNumber, url = trackingUrl),
          notifyCustomer = notifyCustomer,
        ),
      ),
    )

  override suspend fun syncShipmentsWithFulfillments(
    payload: SyncShipmentsWithFulfillmentsRequest,
  ): FulfillmentResult<SyncShipmentsWithFulfillmentsResponse> {
    val orderGid = orderGid(payload.shopifyOrderId)

    val orderBefore = loadOrder(orderGid)
      ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    val existingFulfillmentGids = orderBefore.fulfillments
      .map { it.id }
      .filter { it.isNotBlank() }

    for (fulfillmentGid in existingFulfillmentGids) {
      val r = runCatching {
        execute(FulfillmentCancelMutation(FulfillmentCancelMutation.Variables(fulfillmentGid)))
      }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
      val errs = r.data?.fulfillmentCancel?.userErrors.orEmpty()
      // "already canceled" errors are no-ops; everything else is a real failure.
      val realErrs = errs.filter { !it.message.contains("already", ignoreCase = true) }
      if (realErrs.isNotEmpty()) {
        return FulfillmentResult.Err.UserError(realErrs.map { it.message })
      }
      if (!r.errors.isNullOrEmpty()) {
        return FulfillmentResult.Err.GraphqlError(r.errors.toString())
      }
    }

    val orderAfter = if (existingFulfillmentGids.isEmpty()) {
      orderBefore
    } else {
      loadOrder(orderGid)
        ?: return FulfillmentResult.Err.NotFound("order not found after cancel")
    }

    val newIds = payload.shipments.flatMap { shipment ->
      when (val result = createFulfillmentForShipment(orderAfter, shipment)) {
        is FulfillmentResult.Ok -> result.value
        is FulfillmentResult.Err -> return result
      }
    }
    return FulfillmentResult.Ok(SyncShipmentsWithFulfillmentsResponse(newIds))
  }

  override suspend fun createTrackingEvent(
    payload: TrackingUpdateRequest,
  ): FulfillmentResult<TrackingUpdateResponse> {
    val status = when (val parsed = ParsedFulfillmentStatus.parseFulfillmentEventStatus(payload.status)) {
      is ParsedFulfillmentStatus.Known -> parsed.value
      is ParsedFulfillmentStatus.Unknown ->
        return FulfillmentResult.Err.UserError(listOf("unsupported tracking status: ${parsed.raw}"))
    }

    val orderGid = orderGid(payload.shopifyOrderId)
    val order = loadOrder(orderGid)
      ?: return FulfillmentResult.Err.NotFound("order ${payload.shopifyOrderId} not found")

    val fulfillmentGid = order.fulfillments.find { fulfillment ->
      fulfillment.trackingInfo.any { it.number == payload.trackingNumber }
    }?.id
      ?: return FulfillmentResult.Err.NotFound(
        "no fulfillment with tracking number ${payload.trackingNumber} on order ${payload.shopifyOrderId}",
      )

    val input = FulfillmentEventInput(
      fulfillmentId = fulfillmentGid,
      happenedAt = payload.happenedAt,
      status = status,
      message = payload.message,
    )
    val r = runCatching {
      execute(FulfillmentEventCreateMutation(FulfillmentEventCreateMutation.Variables(input)))
    }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }
    val errs = r.data?.fulfillmentEventCreate?.userErrors.orEmpty()
    if (errs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(errs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphqlError(r.errors.toString())
    }
    val ev = r.data?.fulfillmentEventCreate?.fulfillmentEvent
    val eid = legacyIdFromGid(ev?.id.toString())
      ?: return FulfillmentResult.Err.NotFound("missing fulfillment event id")
    return FulfillmentResult.Ok(TrackingUpdateResponse(fulfillmentEventId = eid))
  }

  override suspend fun registerStandardWebhooks(callbackUrl: String): WebhookRegistrationReport {
    val topics = listOf(
      WebhookSubscriptionTopic.PRODUCTS_UPDATE,
      WebhookSubscriptionTopic.PRODUCTS_CREATE,
      WebhookSubscriptionTopic.PRODUCTS_DELETE,
      WebhookSubscriptionTopic.ORDERS_CREATE,
      WebhookSubscriptionTopic.ORDERS_UPDATED,
    )
    val ordersTopics = setOf(WebhookSubscriptionTopic.ORDERS_CREATE, WebhookSubscriptionTopic.ORDERS_UPDATED)
    val ordersSafeFields = listOf("id", "admin_graphql_api_id")

    val existing = fetchWebhookSubscriptions(callbackUrl, topics)
    val failedTopics = mutableListOf<Pair<WebhookSubscriptionTopic, String>>()
    for (topic in topics) {
      val includeFields = if (topic in ordersTopics) ordersSafeFields else null
      val wh = execute(RegisterWebhook(RegisterWebhook.Variables(topic, callbackUrl, includeFields)))
      val graphqlErrorMsg = wh.errors?.takeIf { it.isNotEmpty() }?.joinToString { it.message }
      val userErrorMsg = wh.data?.webhookSubscriptionCreate?.userErrors.orEmpty()
        .takeIf { it.isNotEmpty() }
        ?.joinToString { ue ->
          val f = ue.field.orEmpty().joinToString(",")
          if (f.isNotBlank()) "$f: ${ue.message}" else ue.message
        }
      val combinedError = listOfNotNull(graphqlErrorMsg, userErrorMsg)
        .joinToString("; ")
        .takeIf { it.isNotBlank() }
      if (combinedError != null) {
        log.warn { "Webhook registration failed shop=${shop.host} topic=$topic error=$combinedError" }
        failedTopics.add(topic to combinedError)
      } else {
        log.info {
          "Webhook registered shop=${shop.host} topic=$topic id=${wh.data?.webhookSubscriptionCreate?.webhookSubscription?.id}"
        }
      }
    }
    val active = fetchWebhookSubscriptions(callbackUrl, topics)
    val existingIds = existing.mapTo(mutableSetOf()) { it.id }
    val added = active.filter { it.id !in existingIds }
    return WebhookRegistrationReport(
      activeSubscriptions = active.sortedForDisplay(),
      addedSubscriptions = added.sortedForDisplay(),
      failedTopics = failedTopics,
    )
  }

  private suspend fun loadOrder(orderGid: String): Order? {
    val r = runCatching {
      execute(GetOrderForDss(GetOrderForDss.Variables(orderGid)))
    }.getOrElse { return null }
    return r.data?.order
  }

  private suspend fun createFulfillmentForShipment(
    order: Order,
    shipment: Shipment,
  ): FulfillmentResult<List<Long>> {
    val foGroups = when (val matchResult = matchShipmentToFulfillmentOrders(order, shipment)) {
      is ShipmentMatchResult.Ok -> matchResult.groups
      is ShipmentMatchResult.NotFound -> return FulfillmentResult.Err.NotFound(matchResult.detail)
      is ShipmentMatchResult.UserError -> return FulfillmentResult.Err.UserError(matchResult.messages)
    }

    val lineItemsByFo = foGroups.entries.map { (fo, inputs) ->
      FulfillmentOrderLineItemsInput(
        fulfillmentOrderId = fo.id,
        fulfillmentOrderLineItems = inputs,
      )
    }

    val tracking = FulfillmentTrackingInput(
      company = shipment.carrier,
      number = shipment.trackingNumber,
      url = shipment.trackingUrl,
    )

    val variables = FulfillmentCreateWithLineItems.Variables(
      lineItemsByFulfillmentOrder = lineItemsByFo,
      tracking = tracking,
      notifyCustomer = false,
    )

    val r = runCatching {
      execute(FulfillmentCreateWithLineItems(variables))
    }.getOrElse { e -> return FulfillmentResult.Err.Network(e.message ?: "network error") }

    val createErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty()
    if (createErrs.isNotEmpty()) {
      return FulfillmentResult.Err.UserError(createErrs.map { it.message })
    }
    if (!r.errors.isNullOrEmpty()) {
      return FulfillmentResult.Err.GraphqlError(r.errors.toString())
    }

    val f = r.data?.fulfillmentCreate?.fulfillment
    val idNum = f?.legacyResourceId?.toLongOrNull() ?: f?.id?.let { legacyIdFromGid(it) }
    return FulfillmentResult.Ok(listOfNotNull(idNum))
  }

  private suspend fun fetchWebhookSubscriptions(
    callbackUrl: String,
    topics: List<WebhookSubscriptionTopic>,
  ): List<WebhookSubscriptionStatus> {
    val result = execute(GetWebhookSubscriptions(GetWebhookSubscriptions.Variables(topics, callbackUrl)))
    if (!result.errors.isNullOrEmpty()) {
      log.warn { "Webhook subscriptions query errors shop=${shop.host} errors=${result.errors}" }
    }

    val nodes = result.data?.webhookSubscriptions?.nodes ?: return emptyList()
    return nodes.map { node ->
      WebhookSubscriptionStatus(
        id = node.id,
        topic = node.topic,
        uri = node.uri,
      )
    }
  }

  private fun List<WebhookSubscriptionStatus>.sortedForDisplay(): List<WebhookSubscriptionStatus> =
    sortedWith(compareBy({ it.topic.name }, { it.uri }, { it.id }))

  /**
   * The single point that injects the per-shop `X-Shopify-Access-Token` header. Every named
   * method above goes through this; no code outside this class constructs a Graphql request
   * (the Konsist `forbid dropnext.graphql.generated outside lib/shopify` rule enforces it).
   */
  private suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): GraphQLClientResponse<T> =
    gqlClient.execute(request) { header("X-Shopify-Access-Token", accessToken) }
}
