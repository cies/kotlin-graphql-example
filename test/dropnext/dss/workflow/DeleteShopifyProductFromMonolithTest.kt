package dropnext.dss.workflow

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private val deletedProduct = ShopifyProductId(503L)


class DeleteShopifyProductFromMonolithTest {

  @Test
  fun `asks the monolith to soft-delete the product's variants under the shop's subdomain`() = runBlocking {
    val monolith = FakeMonolithService()

    deleteShopifyProductFromMonolith(monolith, acmeShop, deletedProduct)

    val request = monolith.deleteProductVariantsCalls.single()
    assert(request.shopifySubdomain == "acme")
    assert(request.productId == 503L)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a monolith rejection is logged with its trace id and does not raise`() {
    val monolith = FakeMonolithService().apply { deleteProductVariantsStatus = 404 }

    val lines = capturingLogs {
      runBlocking { deleteShopifyProductFromMonolith(monolith, acmeShop, deletedProduct) }
    }

    assert(monolith.deleteProductVariantsCalls.size == 1)
    val line = lines.single { "Monolith deleteProductVariants failed" in it }
    // A 4xx is what we sent being refused: a warning, where a 5xx would be an error.
    assert(line.startsWith("WARN"))
    assert("status=404" in line)
    assert("productId=503" in line)
    assert("monolith_trace_id=fake-delete" in line)
  }
}
