package com.example.shopify

import com.example.graphql.generated.RegisterWebhook
import com.example.graphql.generated.URL
import com.example.graphql.generated.enums.WebhookSubscriptionTopic
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.request.header
import org.slf4j.Logger

suspend fun registerStandardWebhooks(
  graphQLClient: GraphQLKtorClient,
  accessToken: String,
  callbackUrl: URL,
  log: Logger,
) {
  val topics =
    listOf(
      WebhookSubscriptionTopic.PRODUCTS_UPDATE,
      WebhookSubscriptionTopic.PRODUCTS_CREATE,
      WebhookSubscriptionTopic.PRODUCTS_DELETE,
      WebhookSubscriptionTopic.ORDERS_CREATE,
      WebhookSubscriptionTopic.ORDERS_UPDATED,
    )
  for (topic in topics) {
    val wh =
      graphQLClient.execute(RegisterWebhook(RegisterWebhook.Variables(topic, callbackUrl))) {
        header("X-Shopify-Access-Token", accessToken)
      }
    val userErrors =
      wh.data?.webhookSubscriptionCreate?.userErrors.orEmpty().joinToString { ue ->
        val f = ue.field.orEmpty().joinToString(",")
        "$f:${ue.message}"
      }
    if (!wh.errors.isNullOrEmpty()) {
      log.warn("Webhook registration GraphQL errors topic=$topic errors=${wh.errors}")
    }
    if (userErrors.isNotEmpty()) {
      log.warn("Webhook registration userErrors topic=$topic $userErrors")
    } else {
      log.info(
        "Webhook registered topic=$topic id=${wh.data?.webhookSubscriptionCreate?.webhookSubscription?.id}",
      )
    }
  }
}
