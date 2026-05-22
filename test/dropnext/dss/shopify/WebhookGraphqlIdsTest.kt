package dropnext.dss.shopify

import kotlin.test.Test

class WebhookGraphqlIdsTest {

  @Test
  fun `parses domain field from webhook JSON`() {
    val domain =
      shopDomainFromWebhookBody(
        """{"id":1001,"domain":"acme.myshopify.com","admin_graphql_api_id":"gid://shopify/Order/1001"}""",
      )
    assert(domain == "acme.myshopify.com")
  }

  @Test
  fun `returns null when domain missing`() {
    assert(shopDomainFromWebhookBody("""{"id":1001}""") == null)
  }
}
