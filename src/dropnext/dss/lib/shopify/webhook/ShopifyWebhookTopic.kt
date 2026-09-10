package dropnext.dss.lib.shopify.webhook

import dropnext.graphql.generated.enums.WebhookSubscriptionTopic


/** Restricting `orders` topics to id-only fields keeps the app exempt from "protected customer data" approval. */
private val ordersSafeFields = listOf("id", "admin_graphql_api_id")

/**
 * Inbound webhook topics this service routes on. [Other] captures any unhandled topic header,
 * preserving the raw string so monitoring can flag unexpected topics by name.
 * A nullable enum would collapse "unknown topic 'foo/bar'" and "missing header" into the same `null`
 * and lose that signal.
 *
 * Each known case carries the matching Shopify Admin Graphql [subscriptionTopic] and the fields the subscription projects,
 * so the post-installation registration (`workflow/registerShopifyWebhooks`) derives everything from [known]:
 * adding a topic in one place is enough.
 */
sealed interface ShopifyWebhookTopic {
  val raw: String

  /** Admin Graphql enum value used for outbound subscription registration. `null` for [Other]. */
  val subscriptionTopic: WebhookSubscriptionTopic?

  /** The payload fields the subscription is restricted to; `null` means the full payload. */
  val includeFields: List<String>? get() = null

  data object ProductsCreate : ShopifyWebhookTopic {
    override val raw = "products/create"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_CREATE
  }

  data object ProductsUpdate : ShopifyWebhookTopic {
    override val raw = "products/update"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_UPDATE
  }

  data object ProductsDelete : ShopifyWebhookTopic {
    override val raw = "products/delete"
    override val subscriptionTopic = WebhookSubscriptionTopic.PRODUCTS_DELETE
  }

  data object OrdersCreate : ShopifyWebhookTopic {
    override val raw = "orders/create"
    override val subscriptionTopic = WebhookSubscriptionTopic.ORDERS_CREATE
    override val includeFields = ordersSafeFields
  }

  data object OrdersUpdated : ShopifyWebhookTopic {
    override val raw = "orders/updated"
    override val subscriptionTopic = WebhookSubscriptionTopic.ORDERS_UPDATED
    override val includeFields = ordersSafeFields
  }

  data class Other(override val raw: String) : ShopifyWebhookTopic {
    override val subscriptionTopic: WebhookSubscriptionTopic? = null
  }

  companion object {
    /** Every named topic the service registers and handles, in install-time registration order. */
    val known: List<ShopifyWebhookTopic> = listOf(
      ProductsUpdate,
      ProductsCreate,
      ProductsDelete,
      OrdersCreate,
      OrdersUpdated,
    )

    fun parse(header: String?): ShopifyWebhookTopic =
      when (val v = header?.trim().orEmpty()) {
        ProductsCreate.raw -> ProductsCreate
        ProductsUpdate.raw -> ProductsUpdate
        ProductsDelete.raw -> ProductsDelete
        OrdersCreate.raw -> OrdersCreate
        OrdersUpdated.raw -> OrdersUpdated
        else -> Other(v)
      }
  }
}
