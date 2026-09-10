package dropnext.dss.workflow

import dropnext.dss.lib.shopify.graphql.FulfillmentLine


/** One Shopify write the shipments sync has decided on; `calculate` plans them, `effect` runs them. */
sealed interface ShopifyMutation {
  data class FulfillmentCancel(val fulfillmentId: String) : ShopifyMutation

  /**
   * [notifyCustomer] is always `false` for this flow: the retailer's own store notifies its
   * customers. [carrier] and [trackingUrl] are forwarded from the shipment payload when present.
   */
  data class FulfillmentCreate(
    val lineItems: List<FulfillmentLine>,
    val trackingNumber: String,
    val carrier: String?,
    val trackingUrl: String?,
    val notifyCustomer: Boolean = false,
  ) : ShopifyMutation
}
