package dropnext.dss.workflow

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!


class DeleteShopifyProductFromMonolithTest {

  @Test
  fun `soft-deletes the variant ids under the shop's subdomain`() = runBlocking {
    val monolith = FakeMonolithService()

    deleteShopifyProductFromMonolith(monolith, acmeShop, listOf(ShopifyVariantId(701L), ShopifyVariantId(702L)))

    val request = monolith.deleteProductVariantsCalls.single()
    assert(request.shopifySubdomain == "acme")
    assert(request.productVariantIds == listOf(701L, 702L))
  }

  /** A `products/delete` body without variants is a product that had none; there is nothing to tell the monolith. */
  @Test
  fun `no variant ids means no monolith call`() = runBlocking {
    val monolith = FakeMonolithService()

    deleteShopifyProductFromMonolith(monolith, acmeShop, emptyList())

    assert(monolith.deleteProductVariantsCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith rejection is logged with its trace id and does not raise`() {
    val monolith = FakeMonolithService().apply { deleteProductVariantsStatus = 404 }

    val lines = capturingLogs {
      runBlocking { deleteShopifyProductFromMonolith(monolith, acmeShop, listOf(ShopifyVariantId(701L))) }
    }

    assert(monolith.deleteProductVariantsCalls.size == 1)
    val line = lines.single { "Monolith deleteProductVariants failed" in it }
    // A 4xx is what we sent being refused: a warning, where a 5xx would be an error.
    assert(line.startsWith("WARN"))
    assert("status=404" in line)
    assert("monolith_trace_id=fake-delete" in line)
  }
}
