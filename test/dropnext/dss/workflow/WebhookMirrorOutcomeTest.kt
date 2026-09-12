package dropnext.dss.workflow

import dropnext.dss.lib.shopify.graphql.ShopifyError
import kotlin.test.Test


/** Which outcomes Shopify is asked to redeliver: the webhook handler's `502` or `200` is read off this. */
class WebhookMirrorOutcomeTest {

  @Test
  fun `a Shopify failure is transient exactly when Shopify's error is retryable`() {
    assert(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))).isTransient)
    assert(!WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Access denied", codes = listOf("ACCESS_DENIED"))).isTransient)
  }

  @Test
  fun `a token the monolith could not be asked for and a blown time budget are transient`() {
    assert(WebhookMirrorOutcome.TokenUnavailable.isTransient)
    assert(WebhookMirrorOutcome.TimedOut.isTransient)
  }
}
