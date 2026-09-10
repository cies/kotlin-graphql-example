package dropnext.dss.domain


/**
 * Summary of the OAuth-time webhook subscription run, used by the install confirmation page.
 *
 * [activeSubscriptions] is the set Shopify returns after the run finishes (what the shop will
 * actually receive events for); [addedSubscriptions] is the subset that did not exist before the
 * run; [failures] carries the error message for each topic that could not be registered.
 */
data class WebhookRegistrationReport(
  val activeSubscriptions: List<WebhookSubscriptionStatus>,
  val addedSubscriptions: List<WebhookSubscriptionStatus>,
  val failures: List<WebhookRegistrationFailure>,
)

/** One subscription as Shopify reports it; [topic] is the Admin API enum name (`PRODUCTS_CREATE`). */
data class WebhookSubscriptionStatus(
  val id: String,
  val topic: String,
  val uri: String,
)

data class WebhookRegistrationFailure(
  val topic: String,
  val error: String,
)
