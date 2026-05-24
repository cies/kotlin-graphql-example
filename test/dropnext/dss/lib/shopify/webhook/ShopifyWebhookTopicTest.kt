package dropnext.dss.lib.shopify.webhook

import kotlin.test.Test

class ShopifyWebhookTopicTest {

  @Test
  fun `parses each known topic header`() {
    assert(ShopifyWebhookTopic.parse("products/create") == ShopifyWebhookTopic.ProductsCreate)
    assert(ShopifyWebhookTopic.parse("products/update") == ShopifyWebhookTopic.ProductsUpdate)
    assert(ShopifyWebhookTopic.parse("products/delete") == ShopifyWebhookTopic.ProductsDelete)
    assert(ShopifyWebhookTopic.parse("orders/create") == ShopifyWebhookTopic.OrdersCreate)
    assert(ShopifyWebhookTopic.parse("orders/updated") == ShopifyWebhookTopic.OrdersUpdated)
  }

  @Test
  fun `trims whitespace before matching`() {
    assert(ShopifyWebhookTopic.parse("  orders/create  ") == ShopifyWebhookTopic.OrdersCreate)
  }

  @Test
  fun `unknown topic falls into Other and preserves the raw value`() {
    val parsed = ShopifyWebhookTopic.parse("shop/redact")
    assert(parsed is ShopifyWebhookTopic.Other)
    assert((parsed as ShopifyWebhookTopic.Other).raw == "shop/redact")
  }

  @Test
  fun `null and empty headers fall into Other with an empty raw — distinct from a real unknown topic`() {
    val fromNull = ShopifyWebhookTopic.parse(null)
    val fromEmpty = ShopifyWebhookTopic.parse("")
    val fromBlank = ShopifyWebhookTopic.parse("   ")
    assert(fromNull is ShopifyWebhookTopic.Other && fromNull.raw == "")
    assert(fromEmpty is ShopifyWebhookTopic.Other && fromEmpty.raw == "")
    assert(fromBlank is ShopifyWebhookTopic.Other && fromBlank.raw == "")
  }

  @Test
  fun `is case sensitive — uppercase is not equivalent to canonical`() {
    val parsed = ShopifyWebhookTopic.parse("ORDERS/CREATE")
    assert(parsed is ShopifyWebhookTopic.Other)
  }
}
