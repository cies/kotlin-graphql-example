package dropnext.dss.shopify

import org.junit.jupiter.api.Test

class ShopDomainsTest {
  @Test
  fun `normalize short handle to myshopify`() {
    assert(normalizeShopDomain("harness-sandbox") == "harness-sandbox.myshopify.com")
    assert(normalizeShopDomain("your-dev-store") == "your-dev-store.myshopify.com")
  }

  @Test
  fun `normalize full myshopify host`() {
    assert(normalizeShopDomain("https://foo.myshopify.com/admin") == "foo.myshopify.com")
  }

  @Test
  fun `reject bad handles`() {
    assert(normalizeShopDomain("") == null)
    assert(normalizeShopDomain("-x") == null)
    assert(normalizeShopDomain("has..dot") == null)
  }
}
