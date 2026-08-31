package dropnext.dss.workflow

import dropnext.dss.lib.ktor.DssError


sealed interface ShopifyMutation {
  data class FulfillmentCancel(val fulfillmentId: String) : ShopifyMutation

  /**
   * [quantity] on each line item is required by Shopify `fulfillmentCreate` (omitted in the
   * original sketch). [notifyCustomer] is Shopify's field name (the sketch said `notifyUser`)
   * and is always `false` for this flow. [carrier] and [trackingUrl] are forwarded from the
   * shipment payload when present.
   */
  data class FulfillmentCreate(
    val lineItems: List<FulfillmentOrderLineItem>,
    val trackingNumber: String,
    val carrier: String?,
    val trackingUrl: String?,
    val notifyCustomer: Boolean = false,
  ) : ShopifyMutation
}

data class FulfillmentOrderLineItem(
  val fulfillmentOrderId: String,
  val lineItemId: String,
  val quantity: Int,
)

sealed interface DetermineShopifyMutationsError {
  data class NotFound(val detail: String) : DetermineShopifyMutationsError

  data class UserError(val messages: List<String>) : DetermineShopifyMutationsError

  data class GraphqlError(val raw: String) : DetermineShopifyMutationsError

  data class Network(val message: String) : DetermineShopifyMutationsError
}

sealed interface ShopifyError {
  data class NotFound(val detail: String) : ShopifyError

  data class UserError(val messages: List<String>) : ShopifyError

  data class GraphqlError(val raw: String) : ShopifyError

  data class Network(val message: String) : ShopifyError
}

/** Empty [errors] means success. [newFulfillmentIds] are legacy ids from successful creates. */
data class EffectShopifyMutationsResult(
  val newFulfillmentIds: List<Long>,
  val errors: List<ShopifyError>,
)

fun DetermineShopifyMutationsError.toDssError(): DssError = when (this) {
  is DetermineShopifyMutationsError.NotFound -> DssError.NotFound(detail)
  is DetermineShopifyMutationsError.UserError -> DssError.InvalidRequest(messages.joinToString("; "))
  is DetermineShopifyMutationsError.GraphqlError -> DssError.UpstreamFailure(raw)
  is DetermineShopifyMutationsError.Network -> DssError.UpstreamFailure(message)
}

fun ShopifyError.toDssError(): DssError = when (this) {
  is ShopifyError.NotFound -> DssError.NotFound(detail)
  is ShopifyError.UserError -> DssError.InvalidRequest(messages.joinToString("; "))
  is ShopifyError.GraphqlError -> DssError.UpstreamFailure(raw)
  is ShopifyError.Network -> DssError.UpstreamFailure(message)
}
