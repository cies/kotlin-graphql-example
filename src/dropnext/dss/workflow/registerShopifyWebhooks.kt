package dropnext.dss.workflow

import com.expediagroup.graphql.client.types.GraphQLClientResponse
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookRegistrationReport
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

private val ordersTopics =
  setOf(WebhookSubscriptionTopic.ORDERS_CREATE, WebhookSubscriptionTopic.ORDERS_UPDATED)

/** Restricting `orders` topics to id-only fields keeps the app exempt from "protected customer data" approval. */
private val ordersSafeFields = listOf("id", "admin_graphql_api_id")

/**
 * Subscribes the shop to every topic the DSS handles ([ShopifyWebhookTopic.known]). Orders
 * subscriptions are restricted to id-only fields so Shopify does not require "protected customer
 * data" approval (the DSS fetches the full order via Graphql after the webhook arrives).
 *
 * Composes [ShopifyGraphqlService.getWebhookSubscriptions] and
 * [ShopifyGraphqlService.registerWebhook].
 */
suspend fun registerShopifyWebhooks(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
): WebhookRegistrationReport {
  val topics = ShopifyWebhookTopic.known.mapNotNull { it.subscriptionTopic }
  val existing = fetchSubscriptions(shopify, callbackUrl, topics)
  val failedTopics = mutableListOf<Pair<WebhookSubscriptionTopic, String>>()
  topics.forEach { topic ->
    val includeFields = if (topic in ordersTopics) ordersSafeFields else null
    val wh = shopify.registerWebhook(topic, callbackUrl, includeFields)
    val combinedError = combineRegistrationErrors(wh)
    if (combinedError != null) {
      log.warn { "Webhook registration failed shop=${shopify.shop.normalizedShopifyHost} topic=$topic error=$combinedError" }
      failedTopics += topic to combinedError
    } else {
      log.info {
        "Webhook registered shop=${shopify.shop.normalizedShopifyHost} topic=$topic " +
          "id=${wh.data?.webhookSubscriptionCreate?.webhookSubscription?.id}"
      }
    }
  }
  val active = fetchSubscriptions(shopify, callbackUrl, topics)
  val existingIds = existing.mapTo(mutableSetOf()) { it.id }
  val added = active.filter { it.id !in existingIds }
  return WebhookRegistrationReport(
    activeSubscriptions = active.sortedForDisplay(),
    addedSubscriptions = added.sortedForDisplay(),
    failedTopics = failedTopics,
  )
}

private fun combineRegistrationErrors(
  wh: GraphQLClientResponse<RegisterWebhook.Result>,
): String? {
  val graphqlErrorMsg = wh.errors?.takeIf { it.isNotEmpty() }?.joinToString { it.message }
  val userErrorMsg = wh.data?.webhookSubscriptionCreate?.userErrors.orEmpty()
    .takeIf { it.isNotEmpty() }
    ?.joinToString { ue ->
      val f = ue.field.orEmpty().joinToString(",")
      if (f.isNotBlank()) "$f: ${ue.message}" else ue.message
    }
  return listOfNotNull(graphqlErrorMsg, userErrorMsg)
    .joinToString("; ")
    .takeIf { it.isNotBlank() }
}

private suspend fun fetchSubscriptions(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
  topics: List<WebhookSubscriptionTopic>,
): List<WebhookSubscriptionStatus> {
  val result = shopify.getWebhookSubscriptions(topics, callbackUrl)
  if (!result.errors.isNullOrEmpty()) {
    log.warn { "Webhook subscriptions query errors shop=${shopify.shop.normalizedShopifyHost} errors=${result.errors}" }
  }
  val nodes = result.data?.webhookSubscriptions?.nodes ?: return emptyList()
  return nodes.map { node ->
    WebhookSubscriptionStatus(id = node.id, topic = node.topic, uri = node.uri)
  }
}

private fun List<WebhookSubscriptionStatus>.sortedForDisplay(): List<WebhookSubscriptionStatus> =
  sortedWith(compareBy({ it.topic.name }, { it.uri }, { it.id }))
