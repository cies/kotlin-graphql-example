package dropnext.dss.lib.shopify.graphql.webhookregistration

import dropnext.graphql.generated.enums.WebhookSubscriptionTopic


/**
 * Summary of the OAuth-time webhook subscription run, used by the install confirmation page.
 *
 * [activeSubscriptions] is the set Shopify returns after the run finishes (what the shop will
 * actually receive events for); [addedSubscriptions] is the subset that did not exist before the
 * run; [failedTopics] carries error messages for topics that could not be registered.
 */
data class WebhookRegistrationReport(
  val activeSubscriptions: List<WebhookSubscriptionStatus>,
  val addedSubscriptions: List<WebhookSubscriptionStatus>,
  val failedTopics: List<Pair<WebhookSubscriptionTopic, String>>,
)

data class WebhookSubscriptionStatus(
  val id: String,
  val topic: WebhookSubscriptionTopic,
  val uri: String,
)
