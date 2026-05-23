package dropnext.dss.lib.shopify.graphql

import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.URL
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header


private val log = KotlinLogging.logger {}

data class WebhookSubscriptionStatus(
  val id: String,
  val topic: WebhookSubscriptionTopic,
  val uri: String,
)

data class WebhookRegistrationReport(
  val activeSubscriptions: List<WebhookSubscriptionStatus>,
  val addedSubscriptions: List<WebhookSubscriptionStatus>,
  val failedTopics: List<Pair<WebhookSubscriptionTopic, String>>,
)


// TODO(cies): these actions can also be put on a service

suspend fun registerStandardWebhooks(
  gqlClient: GraphQLKtorClient,
  accessToken: String,
  callbackUrl: URL,
): WebhookRegistrationReport {
  val topics =
    listOf(
      WebhookSubscriptionTopic.PRODUCTS_UPDATE,
      WebhookSubscriptionTopic.PRODUCTS_CREATE,
      WebhookSubscriptionTopic.PRODUCTS_DELETE,
      WebhookSubscriptionTopic.ORDERS_CREATE,
      WebhookSubscriptionTopic.ORDERS_UPDATED,
    )
  // For orders topics, restrict the webhook payload to only the order ID fields.
  // This avoids Shopify's "protected customer data" approval requirement, since
  // the DSS only needs the ID to make its own Graphql call for the full order.
  val ordersTopics = setOf(WebhookSubscriptionTopic.ORDERS_CREATE, WebhookSubscriptionTopic.ORDERS_UPDATED)
  val ordersSafeFields = listOf("id", "admin_graphql_api_id")

  val existingSubscriptions = fetchWebhookSubscriptions(gqlClient, accessToken, callbackUrl, topics)
  val failedTopics = mutableListOf<Pair<WebhookSubscriptionTopic, String>>()
  for (topic in topics) {
    val includeFields = if (topic in ordersTopics) ordersSafeFields else null
    val wh =
      gqlClient.execute(RegisterWebhook(RegisterWebhook.Variables(topic, callbackUrl, includeFields))) {
        header("X-Shopify-Access-Token", accessToken)
      }
    val graphqlErrorMsg =
      wh.errors?.takeIf { it.isNotEmpty() }?.joinToString { it.message }
    val userErrorMsg =
      wh.data?.webhookSubscriptionCreate?.userErrors.orEmpty().takeIf { it.isNotEmpty() }?.joinToString { ue ->
        val f = ue.field.orEmpty().joinToString(",")
        if (f.isNotBlank()) "$f: ${ue.message}" else ue.message
      }
    val combinedError = listOfNotNull(graphqlErrorMsg, userErrorMsg).joinToString("; ").takeIf { it.isNotBlank() }
    if (combinedError != null) {
      log.warn { "Webhook registration failed topic=$topic error=$combinedError" }
      failedTopics.add(topic to combinedError)
    } else {
      log.info {
        "Webhook registered topic=$topic id=${wh.data?.webhookSubscriptionCreate?.webhookSubscription?.id}"
      }
    }
  }
  val activeSubscriptions = fetchWebhookSubscriptions(gqlClient, accessToken, callbackUrl, topics)
  val existingIds = existingSubscriptions.mapTo(mutableSetOf()) { it.id }
  val addedSubscriptions = activeSubscriptions.filter { subscription -> subscription.id !in existingIds }
  return WebhookRegistrationReport(
    activeSubscriptions = activeSubscriptions.sortedForDisplay(),
    addedSubscriptions = addedSubscriptions.sortedForDisplay(),
    failedTopics = failedTopics,
  )
}

private suspend fun fetchWebhookSubscriptions(
  gqlClient: GraphQLKtorClient,
  accessToken: String,
  callbackUrl: URL,
  topics: List<WebhookSubscriptionTopic>,
): List<WebhookSubscriptionStatus> {
  val result =
    gqlClient.execute(GetWebhookSubscriptions(GetWebhookSubscriptions.Variables(topics, callbackUrl))) {
      header("X-Shopify-Access-Token", accessToken)
    }
  if (!result.errors.isNullOrEmpty()) {
    log.warn { "Webhook subscriptions query errors errors=${result.errors}" }
  }
  return result.data
    ?.webhookSubscriptions
    ?.nodes
    .orEmpty()
    .map { node -> WebhookSubscriptionStatus(id = node.id, topic = node.topic, uri = node.uri) }
}

private fun List<WebhookSubscriptionStatus>.sortedForDisplay(): List<WebhookSubscriptionStatus> =
  sortedWith(compareBy({ it.topic.name }, { it.uri }, { it.id }))
