package dropnext.dss.shopify

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ShopDomainsTest {
  @Test
  fun `normalize short handle to myshopify`() {
    assertEquals("harness-sandbox.myshopify.com", normalizeShopDomain("harness-sandbox"))
    assertEquals("your-dev-store.myshopify.com", normalizeShopDomain("your-dev-store"))
  }

  @Test
  fun `normalize full myshopify host`() {
    assertEquals("foo.myshopify.com", normalizeShopDomain("https://foo.myshopify.com/admin"))
  }

  @Test
  fun `reject bad handles`() {
    assertNull(normalizeShopDomain(""))
    assertNull(normalizeShopDomain("-x"))
    assertNull(normalizeShopDomain("has..dot"))
  }
}
