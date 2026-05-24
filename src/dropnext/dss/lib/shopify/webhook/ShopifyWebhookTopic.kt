package dropnext.dss.lib.shopify.webhook


/**
 * Inbound webhook topics this service routes on. [Other] captures any unhandled topic header,
 * preserving the raw string so monitoring can flag unexpected topics by name. A nullable enum
 * would collapse "unknown topic 'foo/bar'" and "missing header" into the same `null` and lose
 * that signal.
 */
sealed interface ShopifyWebhookTopic {
  val raw: String

  data object ProductsCreate : ShopifyWebhookTopic { override val raw = "products/create" }
  data object ProductsUpdate : ShopifyWebhookTopic { override val raw = "products/update" }
  data object ProductsDelete : ShopifyWebhookTopic { override val raw = "products/delete" }
  data object OrdersCreate : ShopifyWebhookTopic { override val raw = "orders/create" }
  data object OrdersUpdated : ShopifyWebhookTopic { override val raw = "orders/updated" }
  data class Other(override val raw: String) : ShopifyWebhookTopic

  companion object {
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
