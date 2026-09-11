package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.WebhookTopicStatus
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


private const val CALLBACK_URL = "https://dss.example/webhooks/shopify"
private const val OLD_CALLBACK_URL = "https://old-tunnel.example/webhooks/shopify"
private val ALL_TOPICS = setOf("PRODUCTS_CREATE", "PRODUCTS_UPDATE", "PRODUCTS_DELETE", "ORDERS_CREATE", "ORDERS_UPDATED")


/**
 * One row per handled topic, and Shopify is asked to register only what is missing: a reinstall
 * used to re-register everything and show five "already taken" failures beside five active
 * subscriptions. The scan is also what the readiness check answers from, so it is pinned on its own.
 */
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

  // ---------- registering ----------

  @Test
  fun `a first install registers all five topics and reports each as added`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
    assert(report.topics.map { it.topic }.toSet() == ALL_TOPICS)
    assert(report.topics.all { it.status is WebhookTopicStatus.Added })
    assert(report.addedCount == 5)
    assert(report.failures.isEmpty())
    val added = report.topics.first().status as WebhookTopicStatus.Added
    assert(added.subscription.id == "gid://shopify/WebhookSubscription/9999")
    assert(added.subscription.uri == CALLBACK_URL)
  }

  @Test
  fun `restricts orders topics to id-only include fields`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    registerShopifyWebhooks(shopify, CALLBACK_URL)

    val registers = fake.calls.filter { it.operationName == "RegisterWebhook" }
    val ordersCalls = registers.filter { "ORDERS_CREATE" in it.rawBody || "ORDERS_UPDATED" in it.rawBody }
    val productCalls = registers.filter { call ->
      "PRODUCTS_CREATE" in call.rawBody || "PRODUCTS_UPDATE" in call.rawBody || "PRODUCTS_DELETE" in call.rawBody
    }
    assert(ordersCalls.size == 2)
    assert(productCalls.size == 3)
    ordersCalls.forEach { call -> assert("admin_graphql_api_id" in call.rawBody) }
    productCalls.forEach { call -> assert("admin_graphql_api_id" !in call.rawBody) }
  }

  /** The reinstall: everything is already there, so nothing is sent and nothing fails. */
  @Test
  fun `a reinstall registers nothing and reports every topic as active`() = runBlocking {
    stubExistingSubscriptions(ALL_TOPICS.mapIndexed { index, topic -> subscription(index + 1, topic, CALLBACK_URL) })
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    assert(report.activeCount == 5)
    assert(report.addedCount == 0)
    assert(report.failures.isEmpty())
  }

  @Test
  fun `only the topics missing at our callback url are registered`() = runBlocking {
    stubExistingSubscriptions(listOf(subscription(1, "PRODUCTS_CREATE", CALLBACK_URL), subscription(2, "ORDERS_CREATE", CALLBACK_URL)))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 3)
    assert(report.activeCount == 2)
    assert(report.addedCount == 3)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.single { it.topic == "PRODUCTS_DELETE" }.status is WebhookTopicStatus.Added)
  }

  /** A subscription at another URL does not count as ours: the topic is registered here and the old one reported. */
  @Test
  fun `a subscription at an old callback url is stale, and the topic is registered at the current one`() = runBlocking {
    stubExistingSubscriptions(listOf(subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL)))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.staleCount == 1)
    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
  }

  @Test
  fun `a topic Shopify refuses is reported as failed with its user error`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = listOf(RegisterUserError(field = listOf("topic"), message = "scope missing")),
          webhookSubscription = null,
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(report.failures.size == 5)
    assert(report.failures.all { "scope missing" in (it.status as WebhookTopicStatus.Failed).error })
    assert(report.failures.map { it.topic }.toSet() == ALL_TOPICS)
  }

  /** The install never fails on Shopify's account: without a scan every topic is registered and Shopify sorts it out. */
  @Test
  fun `a failed subscriptions query does not fail the install nor stop the registrations`() = runBlocking {
    fake.stubRaw("GetWebhookSubscriptions", """{"data":null,"errors":[{"message":"Throttled"}]}""")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
    assert(report.addedCount == 5)
    assert(report.failures.isEmpty())
  }

  // ---------- scanning ----------

  @Test
  fun `the scan asks Shopify for every handled topic without a url filter`() = runBlocking {
    stubExistingSubscriptions(emptyList())

    scanShopifyWebhooks(shopify, CALLBACK_URL)

    val query = fake.calls.single { it.operationName == "GetWebhookSubscriptions" }
    ALL_TOPICS.forEach { topic -> assert(topic in query.rawBody) }
    assert("\"uri\":null" in query.rawBody || "\"uri\"" !in query.rawBody)
  }

  @Test
  fun `the scan sorts each topic into active, missing and stale without registering anything`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "PRODUCTS_CREATE", CALLBACK_URL),
        subscription(2, "PRODUCTS_CREATE", OLD_CALLBACK_URL),
        subscription(3, "ORDERS_UPDATED", OLD_CALLBACK_URL),
      ),
    )

    val report = (scanShopifyWebhooks(shopify, CALLBACK_URL) as Success).value

    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }
    assert((productsCreate.status as WebhookTopicStatus.Active).subscription.id == "gid://shopify/WebhookSubscription/1")
    assert(productsCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
    val ordersUpdated = report.topics.single { it.topic == "ORDERS_UPDATED" }
    assert(ordersUpdated.status is WebhookTopicStatus.Missing)
    assert(ordersUpdated.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.missingCount == 4)
    assert(report.staleCount == 2)
  }

  @Test
  fun `a failed scan is a failure the caller can answer from`() = runBlocking {
    fake.stubRaw("GetWebhookSubscriptions", """{"data":null,"errors":[{"message":"Throttled"}]}""")
    assert(scanShopifyWebhooks(shopify, CALLBACK_URL) is Failure)
  }

  // ---------- helpers ----------

  private fun subscription(id: Int, topic: String, uri: String) = ExistingSubscription(
    id = "gid://shopify/WebhookSubscription/$id",
    topic = WebhookSubscriptionTopic.valueOf(topic),
    uri = uri,
  )

  private fun stubExistingSubscriptions(subs: List<ExistingSubscription>) {
    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = subs)),
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
