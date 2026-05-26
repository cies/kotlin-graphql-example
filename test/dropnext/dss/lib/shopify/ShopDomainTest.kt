package dropnext.dss.lib.shopify

import kotlin.test.Test

class ShopDomainTest {

  @Test
  fun `normalises full myshopify host`() {
    assert(ShopDomain.parse("acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `normalises uppercase host`() {
    assert(ShopDomain.parse("ACME.MYSHOPIFY.COM")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from https url`() {
    assert(ShopDomain.parse("https://acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from http url`() {
    assert(ShopDomain.parse("http://acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips trailing path from full url`() {
    assert(ShopDomain.parse("https://acme.myshopify.com/admin/orders")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `expands short handle to myshopify host`() {
    assert(ShopDomain.parse("my-store")?.normalizedShopifyHost == "my-store.myshopify.com")
  }

  @Test
  fun `expands single-character handle`() {
    assert(ShopDomain.parse("a")?.normalizedShopifyHost == "a.myshopify.com")
  }

  @Test
  fun `trims whitespace before normalising`() {
    assert(ShopDomain.parse("  acme.myshopify.com  ")?.normalizedShopifyHost == "acme.myshopify.com")
    assert(ShopDomain.parse("  my-store  ")?.normalizedShopifyHost == "my-store.myshopify.com")
  }

  @Test
  fun `returns null for bare other-host names`() {
    assert(ShopDomain.parse("example.com") == null)
    assert(ShopDomain.parse("myshopify.com") == null)
  }

  @Test
  fun `returns null for invalid handle shapes`() {
    assert(ShopDomain.parse("-leading-hyphen") == null)
    assert(ShopDomain.parse("trailing-") == null)
    assert(ShopDomain.parse("with space") == null)
    assert(ShopDomain.parse("") == null)
  }

  @Test
  fun `lowercases uppercase short handle`() {
    assert(ShopDomain.parse("UPPER")?.normalizedShopifyHost == "upper.myshopify.com")
  }

  @Test
  fun `adminGraphqlUrl composes the per-shop endpoint`() {
    val url = ShopDomain.parse("acme.myshopify.com")!!.adminGraphqlUrl("2026-04")
    assert(url == "https://acme.myshopify.com/admin/api/2026-04/graphql.json")
  }

  @Test
  fun `subdomainShort strips the myshopify suffix`() {
    assert(ShopDomain.parse("acme.myshopify.com")!!.subdomainOnly == "acme")
  }

  @Test
  fun `subdomainShort returns short handle for short input`() {
    assert(ShopDomain.parse("acme")!!.subdomainOnly == "acme")
  }
}
