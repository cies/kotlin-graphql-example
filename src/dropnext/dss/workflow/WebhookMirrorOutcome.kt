package dropnext.dss.workflow

import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.shopify.graphql.ShopifyError


/**
 * What mirroring one Shopify webhook into the monolith came to. The handler answers Shopify from
 * this: a `200` when redelivering the same webhook could not go better, a `5xx` when it could, so
 * Shopify's own redelivery (with backoff, for up to two days) is the retry.
 */
sealed interface WebhookMirrorOutcome {
  /** The monolith has what the webhook announced, whether it was new to it or not. */
  data object Mirrored : WebhookMirrorOutcome

  /** Nothing to mirror, or nothing this service can do about it: a redelivery would end the same way. */
  data class Skipped(val reason: WebhookSkipReason) : WebhookMirrorOutcome

  data class ShopifyFailed(val error: ShopifyError) : WebhookMirrorOutcome

  data class MonolithFailed(val error: MonolithError) : WebhookMirrorOutcome
}

/** Why a delivery was skipped, as a closed set so a log query or a dashboard filter can count each kind. */
enum class WebhookSkipReason {
  NO_SHOP_DOMAIN,
  NO_ADMIN_TOKEN,
  NO_RESOURCE_ID,
  PRODUCT_GONE,
  NO_MAPPABLE_LINES,
  TOPIC_NOT_MIRRORED,
}

/**
 * Whether a redelivery has a chance: Shopify or the monolith not answering, throttling, or answering
 * a `5xx` passes; a token Shopify refuses, a request the monolith refuses, or a resource that is
 * gone does not, and answering a `5xx` for those would only make Shopify hammer a closed door.
 */
val WebhookMirrorOutcome.isTransient: Boolean
  get() = when (this) {
    is WebhookMirrorOutcome.Mirrored, is WebhookMirrorOutcome.Skipped -> false
    is WebhookMirrorOutcome.ShopifyFailed -> when (error) {
      is ShopifyError.Network, is ShopifyError.HttpError, is ShopifyError.GraphqlError -> true
      is ShopifyError.TokenRejected, is ShopifyError.UserError, is ShopifyError.NotFound -> false
    }
    is WebhookMirrorOutcome.MonolithFailed -> when (val error = error) {
      is MonolithError.Transport, is MonolithError.Undecodable -> true
      is MonolithError.Rejected -> error.status >= 500
    }
  }
