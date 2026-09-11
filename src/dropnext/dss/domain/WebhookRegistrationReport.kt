package dropnext.dss.domain


/**
 * One row per webhook topic the service handles, answering the three questions the install page and
 * the readiness check are asked: is the shop subscribed at our callback URL, did this run change that,
 * and if it could not, why. [WebhookTopicRegistration.stale] lists subscriptions for the same topic
 * that point elsewhere (an earlier tunnel, another environment) and still receive the deliveries.
 */
data class WebhookRegistrationReport(val topics: List<WebhookTopicRegistration>) {
  val activeCount: Int get() = topics.count { it.status is WebhookTopicStatus.Active }
  val addedCount: Int get() = topics.count { it.status is WebhookTopicStatus.Added }
  val missingCount: Int get() = topics.count { it.status is WebhookTopicStatus.Missing }
  val staleCount: Int get() = topics.sumOf { it.stale.size }
  val failures: List<WebhookTopicRegistration> get() = topics.filter { it.status is WebhookTopicStatus.Failed }
}

/** [topic] is the Admin API enum name (`PRODUCTS_CREATE`). */
data class WebhookTopicRegistration(
  val topic: String,
  val status: WebhookTopicStatus,
  val stale: List<WebhookSubscriptionStatus> = emptyList(),
)

sealed interface WebhookTopicStatus {
  /** Subscribed at our callback URL before this run; nothing was sent to Shopify for it. */
  data class Active(val subscription: WebhookSubscriptionStatus) : WebhookTopicStatus

  /** Subscribed by this run. */
  data class Added(val subscription: WebhookSubscriptionStatus) : WebhookTopicStatus

  /** Not subscribed at our callback URL, and this run did not try: a read-only scan. */
  data object Missing : WebhookTopicStatus

  /** This run tried and Shopify refused; [error] is its user error, which the reader is about to act on. */
  data class Failed(val error: String) : WebhookTopicStatus
}

/** One subscription as Shopify reports it; [topic] is the Admin API enum name (`PRODUCTS_CREATE`). */
data class WebhookSubscriptionStatus(
  val id: String,
  val topic: String,
  val uri: String,
)
