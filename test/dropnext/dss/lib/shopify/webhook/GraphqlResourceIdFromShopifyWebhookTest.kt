package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyVariantId
import kotlin.test.Test


class GraphqlResourceIdFromShopifyWebhookTest {

  @Test
  fun `graphqlResourceIdFromShopifyWebhook returns admin_graphql_api_id when present`() {
    val id = graphqlResourceIdFromShopifyWebhook(
      topic = "orders/create",
      bodyUtf8 = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""",
    )
    assert(id == "gid://shopify/Order/1001")
  }

  @Test
  fun `graphqlResourceIdFromShopifyWebhook builds gid from numeric id by topic`() {
    val orderId = graphqlResourceIdFromShopifyWebhook("orders/updated", """{"id":2002}""")
    assert(orderId == "gid://shopify/Order/2002")
    val productId = graphqlResourceIdFromShopifyWebhook("products/update", """{"id":3003}""")
    assert(productId == "gid://shopify/Product/3003")
  }

  @Test
  fun `graphqlResourceIdFromShopifyWebhook returns null on unknown topic without admin_graphql_api_id`() {
    assert(graphqlResourceIdFromShopifyWebhook("customers/create", """{"id":4004}""") == null)
  }

  @Test
  fun `variantIdsFromProductWebhook extracts variant ids`() {
    val ids = variantIdsFromProductWebhook(
      """{"id":1,"variants":[{"id":101},{"id":102},{"id":103}]}""",
    )
    assert(ids == listOf(101L, 102L, 103L).map(::ShopifyVariantId))
  }

  @Test
  fun `variantIdsFromProductWebhook returns empty list when variants missing`() {
    assert(variantIdsFromProductWebhook("""{"id":1}""") == emptyList<ShopifyVariantId>())
  }
}
