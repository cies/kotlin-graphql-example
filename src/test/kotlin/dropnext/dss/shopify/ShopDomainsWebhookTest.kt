package dropnext.dss.shopify

import kotlin.test.Test

class ShopDomainsWebhookTest {

  @Test
  fun `prefers X-Shopify-Shop-Domain header`() {
    val host = shopMyshopifyHostFromWebhook("DropNext-Staging.myshopify.com", "other.myshopify.com")
    assert(host == "dropnext-staging.myshopify.com")
  }

  @Test
  fun `falls back to webhook body domain when header missing`() {
    val host = shopMyshopifyHostFromWebhook(null, "acme.myshopify.com")
    assert(host == "acme.myshopify.com")
  }

  @Test
  fun `returns null when neither source is valid`() {
    assert(shopMyshopifyHostFromWebhook(null, null) == null)
    assert(shopMyshopifyHostFromWebhook("", "!!!") == null)
  }
}
