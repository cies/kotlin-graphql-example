package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import kotlin.test.Test

class RenderOAuthInstallPageTest {

  private fun renderBase(
    shop: String = "acme.myshopify.com",
    monolithPersist: MonolithPersistOutcome = MonolithPersistOutcome.Persisted(storeId = 1L),
    activeSubscriptions: List<WebhookSubscriptionStatus> = emptyList(),
    addedSubscriptions: List<WebhookSubscriptionStatus> = emptyList(),
    failedTopics: List<Pair<WebhookSubscriptionTopic, String>> = emptyList(),
  ): String =
    renderOAuthInstallPage(
      shop = shop,
      shopId = 9988L,
      monolithPersist = monolithPersist,
      productEdgeCount = 3,
      webhookCallbackUrl = "https://dss.example.com/webhooks/shopify",
      activeSubscriptions = activeSubscriptions,
      addedSubscriptions = addedSubscriptions,
      failedTopics = failedTopics,
    )

  @Test
  fun `renders a complete html document with App installed heading`() {
    val html = renderBase()
    assert(html.startsWith("<!DOCTYPE html>"))
    assert("<h1>App installed</h1>" in html)
    assert("Shop: acme.myshopify.com (id 9988)" in html)
  }

  @Test
  fun `escapes html-significant characters in shop`() {
    val malicious = "evil<script>alert(1)</script>.myshopify.com"
    val html = renderBase(shop = malicious)
    assert("<script>alert(1)</script>" !in html)
    assert("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
  }

  @Test
  fun `escapes html-significant characters in failure error messages`() {
    val html = renderBase(
      failedTopics = listOf(
        WebhookSubscriptionTopic.PRODUCTS_CREATE to "<img src=x onerror=alert(1)>",
      ),
    )
    assert("<img src=x onerror=alert(1)>" !in html)
    assert("&lt;img src=x onerror=alert(1)&gt;" in html)
  }

  @Test
  fun `monolith persisted renders the success notice with storeId`() {
    val html = renderBase(monolithPersist = MonolithPersistOutcome.Persisted(storeId = 42L))
    assert("Shopify token saved via monolith" in html)
    assert("store_id=42" in html)
  }

  @Test
  fun `monolith failed renders status and detail`() {
    val html = renderBase(
      monolithPersist = MonolithPersistOutcome.Failed(httpStatus = 503, detail = "upstream timeout"),
    )
    assert("HTTP status 503" in html)
    assert("upstream timeout" in html)
  }

  @Test
  fun `monolith failed truncates very long detail`() {
    val detail = "x".repeat(800)
    val html = renderBase(
      monolithPersist = MonolithPersistOutcome.Failed(httpStatus = 500, detail = detail),
    )
    assert("x".repeat(400) in html)
    assert("x".repeat(401) !in html)
  }

  @Test
  fun `empty subscription lists render a None bullet`() {
    val html = renderBase()
    // One "None" bullet per empty list — both `activeSubscriptions` and `addedSubscriptions` default to empty.
    val noneCount = "<li>None</li>".toRegex().findAll(html).count()
    assert(noneCount == 2)
  }

  @Test
  fun `non-empty subscription list renders one li per entry with id`() {
    val subs = listOf(
      WebhookSubscriptionStatus(
        id = "gid://shopify/WebhookSubscription/1",
        topic = WebhookSubscriptionTopic.PRODUCTS_CREATE,
        uri = "https://dss.example.com/webhooks/shopify",
      ),
      WebhookSubscriptionStatus(
        id = "gid://shopify/WebhookSubscription/2",
        topic = WebhookSubscriptionTopic.ORDERS_CREATE,
        uri = "https://dss.example.com/webhooks/shopify",
      ),
    )
    val html = renderBase(activeSubscriptions = subs)
    assert("PRODUCTS_CREATE" in html)
    assert("ORDERS_CREATE" in html)
    assert("gid://shopify/WebhookSubscription/1" in html)
    assert("gid://shopify/WebhookSubscription/2" in html)
  }

  @Test
  fun `no failure block when failedTopics is empty`() {
    val html = renderBase(failedTopics = emptyList())
    assert("Webhook registrations that failed" !in html)
  }

  @Test
  fun `failure block lists each failed topic`() {
    val html = renderBase(
      failedTopics = listOf(
        WebhookSubscriptionTopic.PRODUCTS_CREATE to "permission denied",
        WebhookSubscriptionTopic.ORDERS_UPDATED to "scope missing",
      ),
    )
    assert("Webhook registrations that failed" in html)
    assert("PRODUCTS_CREATE" in html)
    assert("ORDERS_UPDATED" in html)
    assert("permission denied" in html)
    assert("scope missing" in html)
    assert("SHOPIFY_SCOPES" in html)
  }

  @Test
  fun `robots meta is set to noindex nofollow`() {
    val html = renderBase()
    assert("robots" in html)
    assert("noindex" in html)
    assert("nofollow" in html)
  }
}
