package dropnext.dss.lib.ktor

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.graphql.generated.ShopIdentity
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private const val SHOP_IDENTITY = """{"data":{"shop":{"id":"gid://shopify/Shop/1","myshopifyDomain":"acme.myshopify.com"}}}"""


/** Through the real Graphql client on the shared client, so the operation name is read from the body production sends. */
class ShopifyDeprecationWarningsTest {

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a deprecation Shopify reports is warned about once per operation and reason`() {
    val warnings = warningsFor(repeat = 2, headers = mapOf(SHOPIFY_DEPRECATION_HEADER to "Shop.products, Shop.product"))
    assert(warnings.size == 1)
    assert(warnings.single().startsWith("WARN"))
    assert("operation=ShopIdentity" in warnings.single())
    assert("reason=\"Shop.products, Shop.product\"" in warnings.single())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a response without the header is not warned about`() {
    assert(warningsFor(repeat = 1, headers = emptyMap()).isEmpty())
  }

  private fun warningsFor(repeat: Int, headers: Map<String, String>): List<String> {
    val server = FakeShopifyGraphqlServer()
    val port = server.start()
    val httpClient = createSharedHttpClient()
    try {
      server.stubRaw("ShopIdentity", SHOP_IDENTITY, headers = headers)
      val gqlClient = GraphQLKtorClient(URI(shopifyGraphqlUrl(port)).toURL(), httpClient)
      return capturingLogs { runBlocking { repeat(repeat) { gqlClient.execute(ShopIdentity()) } } }
        .filter { "deprecated API use" in it }
    } finally {
      httpClient.close()
      server.stop()
    }
  }
}
