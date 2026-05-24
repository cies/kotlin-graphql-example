package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.testing.fake.FakeShopifyGraphqlServer
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscription as ExistingSubscription
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.registerwebhook.UserError as RegisterUserError
import dropnext.graphql.generated.registerwebhook.WebhookSubscription as NewSubscription
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class ShopifyWebhookRegistrationTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeTest
  fun setUp() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = HttpClient(OkHttp) {
      engine { config { connectTimeout(2, TimeUnit.SECONDS); readTimeout(5, TimeUnit.SECONDS) } }
      install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 2_000
        socketTimeoutMillis = 5_000
      }
    }
    val url = URI("http://localhost:$port/admin/api/2026-04/graphql.json").toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, "tok")
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

    val report = shopify.registerStandardWebhooks("https://dss.example/webhooks/shopify")

    val registers = fake.calls.filter { it.operationName == "RegisterWebhook" }
    assert(registers.size == 5)
    assert(report.failedTopics.isEmpty())
  }

  @Test
  fun `restricts orders topics to id-only include fields`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    shopify.registerStandardWebhooks("https://dss.example/webhooks/shopify")

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

    val report = shopify.registerStandardWebhooks("https://dss.example/webhooks/shopify")
    assert(report.failedTopics.size == 5)
    assert(report.failedTopics.all { "duplicate subscription" in it.second })
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

    val report = shopify.registerStandardWebhooks("https://dss.example/webhooks/shopify")
    assert(report.addedSubscriptions.isEmpty())
    assert(report.activeSubscriptions.size == active.size)
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
