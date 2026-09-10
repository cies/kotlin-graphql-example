package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.WebhookRegistrationFailure
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Subscribes the shop to every topic the DSS handles ([ShopifyWebhookTopic.known]), each with the
 * payload fields the topic declares (the `orders` topics are id-only so Shopify does not require
 * "protected customer data" approval; the DSS fetches the full order via Graphql after the
 * webhook arrives).
 *
 * Composes [ShopifyGraphqlService.webhookSubscriptions] and [ShopifyGraphqlService.registerWebhook].
 */
suspend fun registerShopifyWebhooks(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
): WebhookRegistrationReport {
  val topics = ShopifyWebhookTopic.known.filter { it.subscriptionTopic != null }
  val subscriptionTopics = topics.mapNotNull { it.subscriptionTopic }
  val existing = fetchSubscriptions(shopify, callbackUrl, subscriptionTopics)
  val failures = topics.mapNotNull { topic ->
    val subscriptionTopic = topic.subscriptionTopic ?: return@mapNotNull null
    when (val registered = shopify.registerWebhook(subscriptionTopic, callbackUrl, topic.includeFields)) {
      is Success -> {
        log.info { "Webhook registered shop=${shopify.shop.normalizedShopifyHost} topic=$subscriptionTopic id=${registered.value}" }
        null
      }

      is Failure -> {
        log.warn { "Webhook registration failed shop=${shopify.shop.normalizedShopifyHost} topic=$subscriptionTopic error=${registered.reason.message}" }
        WebhookRegistrationFailure(subscriptionTopic.name, registered.reason.message)
      }
    }
  }
  val active = fetchSubscriptions(shopify, callbackUrl, subscriptionTopics)
  val existingIds = existing.mapTo(mutableSetOf()) { it.id }
  val added = active.filter { it.id !in existingIds }
  return WebhookRegistrationReport(
    activeSubscriptions = active.sortedForDisplay(),
    addedSubscriptions = added.sortedForDisplay(),
    failures = failures,
  )
}

private suspend fun fetchSubscriptions(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
  topics: List<dropnext.graphql.generated.enums.WebhookSubscriptionTopic>,
): List<WebhookSubscriptionStatus> =
  when (val result = shopify.webhookSubscriptions(topics, callbackUrl)) {
    is Success -> result.value
    is Failure -> {
      log.warn { "Webhook subscriptions query failed shop=${shopify.shop.normalizedShopifyHost} error=${result.reason.message}" }
      emptyList()
    }
  }

private fun List<WebhookSubscriptionStatus>.sortedForDisplay(): List<WebhookSubscriptionStatus> =
  sortedWith(compareBy({ it.topic }, { it.uri }, { it.id }))
