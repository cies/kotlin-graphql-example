package dropnext.dss.lib.shopify

import dropnext.dss.lib.shopify.ShopDomain
import kotlin.test.Test

class ShopDomainWebhookTest {

  @Test
  fun `prefers X-Shopify-Shop-Domain header`() {
    val shop = ShopDomain.fromWebhook("DropNext-Staging.myshopify.com", "other.myshopify.com")
    assert(shop?.host == "dropnext-staging.myshopify.com")
  }

  @Test
  fun `falls back to webhook body domain when header missing`() {
    val shop = ShopDomain.fromWebhook(null, "acme.myshopify.com")
    assert(shop?.host == "acme.myshopify.com")
  }

  @Test
  fun `returns null when neither source is valid`() {
    assert(ShopDomain.fromWebhook(null, null) == null)
    assert(ShopDomain.fromWebhook("", "!!!") == null)
  }
}
