package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscription as ExistingSubscription
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.registerwebhook.UserError as RegisterUserError
import dropnext.graphql.generated.registerwebhook.WebhookSubscription as NewSubscription
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import io.ktor.client.HttpClient
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class RegisterShopifyWebhooksTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeTest
  fun setUp() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = testHttpClient()
    val url = URI(shopifyGraphqlUrl(port)).toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, ShopifyAdminToken("tok"))
  }

  @AfterTest
  fun tearDown() {
    httpClient.close()
    fake.stop()
  }

  @Test
  fun `registers all five standard topics and reports added subscriptions`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify,"https://dss.example/webhooks/shopify")

    val registers = fake.calls.filter { it.operationName == "RegisterWebhook" }
    assert(registers.size == 5)
    assert(report.failures.isEmpty())
  }

  @Test
  fun `restricts orders topics to id-only include fields`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    registerShopifyWebhooks(shopify,"https://dss.example/webhooks/shopify")

    val registers = fake.calls.filter { it.operationName == "RegisterWebhook" }
    val ordersCalls = registers.filter { call ->
      val raw = call.rawBody
      "ORDERS_CREATE" in raw || "ORDERS_UPDATED" in raw
    }
    val productCalls = registers.filter { call ->
      val raw = call.rawBody
      "PRODUCTS_CREATE" in raw || "PRODUCTS_UPDATE" in raw || "PRODUCTS_DELETE" in raw
    }
    assert(ordersCalls.size == 2)
    assert(productCalls.size == 3)
    ordersCalls.forEach { call -> assert("admin_graphql_api_id" in call.rawBody) }
    productCalls.forEach { call -> assert("admin_graphql_api_id" !in call.rawBody) }
  }

  @Test
  fun `records failed topics when userErrors are returned`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = listOf(RegisterUserError(field = listOf("topic"), message = "duplicate subscription")),
          webhookSubscription = null,
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )

    val report = registerShopifyWebhooks(shopify, "https://dss.example/webhooks/shopify")
    assert(report.failures.size == 5)
    assert(report.failures.all { "duplicate subscription" in it.error })
    assert(report.failures.map { it.topic }.toSet() == setOf("PRODUCTS_CREATE", "PRODUCTS_UPDATE", "PRODUCTS_DELETE", "ORDERS_CREATE", "ORDERS_UPDATED"))
  }

  @Test
  fun `addedSubscriptions is empty when the active set already contains everything`() = runBlocking {
    val active = listOf(
      ExistingSubscription(
        id = "gid://shopify/WebhookSubscription/1",
        topic = WebhookSubscriptionTopic.PRODUCTS_CREATE,
        uri = "https://dss.example/webhooks/shopify",
      ),
      ExistingSubscription(
        id = "gid://shopify/WebhookSubscription/2",
        topic = WebhookSubscriptionTopic.ORDERS_CREATE,
        uri = "https://dss.example/webhooks/shopify",
      ),
    )
    stubExistingSubscriptions(active)
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify,"https://dss.example/webhooks/shopify")
    assert(report.addedSubscriptions.isEmpty())
    assert(report.activeSubscriptions.size == active.size)
  }

  @Test
  fun `a subscription the run created is reported as added and as active`() = runBlocking {
    val created = ExistingSubscription(
      id = "gid://shopify/WebhookSubscription/9999",
      topic = WebhookSubscriptionTopic.PRODUCTS_CREATE,
      uri = "https://dss.example/webhooks/shopify",
    )
    fake.stubDataSequence(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result.serializer(),
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList())),
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = listOf(created))),
    )
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, "https://dss.example/webhooks/shopify")

    assert(report.addedSubscriptions.map { it.id } == listOf("gid://shopify/WebhookSubscription/9999"))
    assert(report.activeSubscriptions.map { it.id } == listOf("gid://shopify/WebhookSubscription/9999"))
  }

  @Test
  fun `a subscription that existed before the run is active but not added`() = runBlocking {
    val existing = ExistingSubscription(
      id = "gid://shopify/WebhookSubscription/1",
      topic = WebhookSubscriptionTopic.ORDERS_CREATE,
      uri = "https://dss.example/webhooks/shopify",
    )
    val created = ExistingSubscription(
      id = "gid://shopify/WebhookSubscription/2",
      topic = WebhookSubscriptionTopic.PRODUCTS_DELETE,
      uri = "https://dss.example/webhooks/shopify",
    )
    fake.stubDataSequence(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result.serializer(),
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = listOf(existing))),
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = listOf(created, existing))),
    )
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, "https://dss.example/webhooks/shopify")

    assert(report.addedSubscriptions.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
    // Sorted for the page: by topic, so ORDERS_CREATE before PRODUCTS_DELETE whatever Shopify's order was.
    assert(report.activeSubscriptions.map { it.topic } == listOf("ORDERS_CREATE", "PRODUCTS_DELETE"))
  }

  /** The install never fails on Shopify's account: a subscriptions query that fails still leaves every topic registered. */
  @Test
  fun `a failed subscriptions query does not fail the install nor stop the registrations`() = runBlocking {
    fake.stubRaw("GetWebhookSubscriptions", """{"data":null,"errors":[{"message":"Throttled"}]}""")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, "https://dss.example/webhooks/shopify")

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
    assert(report.failures.isEmpty())
    assert(report.activeSubscriptions.isEmpty())
    assert(report.addedSubscriptions.isEmpty())
  }

  // ---------- helpers ----------

  private fun stubExistingSubscriptions(subs: List<ExistingSubscription>) {

    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(
        webhookSubscriptions = WebhookSubscriptionConnection(nodes = subs),
      ),
      GetWebhookSubscriptions.Result.serializer(),
    )
  }

  private fun stubRegisterOk() {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = emptyList(),
          webhookSubscription = NewSubscription(
            id = "gid://shopify/WebhookSubscription/9999",
            topic = WebhookSubscriptionTopic.PRODUCTS_CREATE,
            includeFields = emptyList(),
          ),
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )
  }
}
