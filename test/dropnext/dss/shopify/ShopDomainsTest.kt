package dropnext.dss.shopify

import kotlin.test.Test

class ShopDomainsTest {

  @Test
  fun `normalizes full myshopify host`() {
    assert(normalizeShopDomain("acme.myshopify.com") == "acme.myshopify.com")
  }

  @Test
  fun `normalizes uppercase host`() {
    assert(normalizeShopDomain("ACME.MYSHOPIFY.COM") == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from https url`() {
    assert(normalizeShopDomain("https://acme.myshopify.com") == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from http url`() {
    assert(normalizeShopDomain("http://acme.myshopify.com") == "acme.myshopify.com")
  }

  @Test
  fun `strips trailing path from full url`() {
    assert(normalizeShopDomain("https://acme.myshopify.com/admin/orders") == "acme.myshopify.com")
  }

  @Test
  fun `expands short handle to myshopify host`() {
    assert(normalizeShopDomain("my-store") == "my-store.myshopify.com")
  }

  @Test
  fun `expands single-character handle`() {
    assert(normalizeShopDomain("a") == "a.myshopify.com")
  }

  @Test
  fun `trims whitespace before normalising`() {
    assert(normalizeShopDomain("  acme.myshopify.com  ") == "acme.myshopify.com")
    assert(normalizeShopDomain("  my-store  ") == "my-store.myshopify.com")
  }

  @Test
  fun `returns null for bare other-host names`() {
    assert(normalizeShopDomain("example.com") == null)
    assert(normalizeShopDomain("myshopify.com") == null)
  }

  @Test
  fun `returns null for invalid handle shapes`() {
    assert(normalizeShopDomain("-leading-hyphen") == null)
    assert(normalizeShopDomain("trailing-") == null)
    assert(normalizeShopDomain("with space") == null)
    assert(normalizeShopDomain("") == null)
  }

  @Test
  fun `lowercases uppercase short handle`() {
    assert(normalizeShopDomain("UPPER") == "upper.myshopify.com")
  }

  @Test
  fun `adminGraphqlJsonUrl composes the per-shop endpoint`() {
    assert(adminGraphqlJsonUrl("acme.myshopify.com", "2026-04") == "https://acme.myshopify.com/admin/api/2026-04/graphql.json")
  }

  @Test
  fun `shopifySubdomainShort strips the myshopify suffix`() {
    assert(shopifySubdomainShort("acme.myshopify.com") == "acme")
  }

  @Test
  fun `shopifySubdomainShort returns input when no suffix`() {
    assert(shopifySubdomainShort("acme") == "acme")
  }
}
