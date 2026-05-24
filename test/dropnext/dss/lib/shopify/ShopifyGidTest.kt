package dropnext.dss.lib.shopify

import dropnext.dss.lib.shopify.fulfillmentGid
import dropnext.dss.lib.shopify.fulfillmentOrderGid
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.dss.lib.shopify.shopifyShopIdFromShopGid
import kotlin.test.Test

class ShopifyGidTest {

  @Test
  fun `legacyIdFromGid extracts the trailing numeric id`() {
    assert(legacyIdFromGid("gid://shopify/Order/123") == 123L)
    assert(legacyIdFromGid("gid://shopify/Product/9876543210") == 9876543210L)
  }

  @Test
  fun `legacyIdFromGid returns null when no numeric tail is present`() {
    assert(legacyIdFromGid("gid://shopify/Order/") == null)
    assert(legacyIdFromGid("gid://shopify/Order/abc") == null)
    assert(legacyIdFromGid("") == null)
  }

  @Test
  fun `orderGid round-trips through legacyIdFromGid`() {
    assert(legacyIdFromGid(orderGid(1001L)) == 1001L)
    assert(orderGid(1001L) == "gid://shopify/Order/1001")
  }

  @Test
  fun `fulfillmentGid round-trips through legacyIdFromGid`() {
    assert(legacyIdFromGid(fulfillmentGid(42L)) == 42L)
    assert(fulfillmentGid(42L) == "gid://shopify/Fulfillment/42")
  }

  @Test
  fun `fulfillmentOrderGid round-trips through legacyIdFromGid`() {
    assert(legacyIdFromGid(fulfillmentOrderGid(7L)) == 7L)
    assert(fulfillmentOrderGid(7L) == "gid://shopify/FulfillmentOrder/7")
  }

  @Test
  fun `shopifyShopIdFromShopGid is an alias for legacyIdFromGid`() {
    assert(shopifyShopIdFromShopGid("gid://shopify/Shop/55") == 55L)
    assert(shopifyShopIdFromShopGid("gid://shopify/Shop/") == null)
  }
}
