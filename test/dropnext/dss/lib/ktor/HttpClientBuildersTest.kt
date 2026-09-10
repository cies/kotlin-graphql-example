package dropnext.dss.lib.ktor

import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import io.ktor.callid.withCallId
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


/**
 * The two differences between the clients, and the reasons they exist: the monolith client retries a
 * dropped connection, the shared client never does, because a retried `fulfillmentCreate` would ship the
 * same parcel twice and Shopify's mutations carry no idempotency key. And the monolith client forwards
 * the request's trace id, the shared client does not, because Shopify has no use for it.
 */
class HttpClientBuildersTest {

  @Test
  fun `the monolith client forwards the trace id from the coroutine context as X-Trace-Id`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createMonolithHttpClient(createSharedHttpClient())
    try {
      runBlocking { withCallId("trace-7") { client.get("http://localhost:$port/probe") } }
      assert(upstream.requests.single().headers["X-Trace-Id"] == listOf("trace-7"))
    } finally {
      client.close()
      upstream.stop()
    }
  }

  @Test
  fun `the shared client sends no X-Trace-Id even inside a request`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createSharedHttpClient()
    try {
      runBlocking { withCallId("trace-7") { client.get("http://localhost:$port/probe") } }
      assert(upstream.requests.single().headers["X-Trace-Id"] == null)
    } finally {
      client.close()
      upstream.stop()
    }
  }

  @Test
  fun `the monolith client retries a dropped connection and succeeds`() {
    FakeFlakyServer(failFirstConnections = 1).use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient())
      try {
        val response: HttpResponse = runBlocking { client.get(upstream.baseUrl) }
        assert(response.status == HttpStatusCode.OK)
        assert(upstream.connectionCount == 2)
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun `the shared client does not retry a dropped connection`() {
    FakeFlakyServer().use { upstream ->
      val client = createSharedHttpClient()
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.isFailure)
        assert(upstream.connectionCount == 1)
      } finally {
        client.close()
      }
    }
  }
}
