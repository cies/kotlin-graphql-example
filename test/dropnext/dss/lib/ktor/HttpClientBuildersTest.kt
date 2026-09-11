package dropnext.dss.lib.ktor

import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import io.ktor.callid.withCallId
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
  fun `the monolith client gives up after three retries of a dropped connection`() {
    FakeFlakyServer().use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient())
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.isFailure)
        assert(upstream.connectionCount == 1 + MONOLITH_MAX_RETRIES)
      } finally {
        client.close()
      }
    }
  }

  /** Every monolith endpoint the DSS calls is idempotent, so a `5xx` from a deploy window is worth a second try. */
  @Test
  fun `the monolith client retries a server error and succeeds`() {
    val upstream = FakeMonolithHttpServer()
    val port = upstream.start()
    val client = createMonolithHttpClient(createSharedHttpClient())
    try {
      upstream.enqueue(HttpStatusCode.ServiceUnavailable, "")
      upstream.enqueue(HttpStatusCode.OK, "{}")
      val response: HttpResponse = runBlocking { client.get("http://localhost:$port/probe") }
      assert(response.status == HttpStatusCode.OK)
      assert(upstream.requests.size == 2)
    } finally {
      client.close()
      upstream.stop()
    }
  }

  /** A timeout is the budget running out, not the monolith failing: repeating it would only hold the webhook longer. */
  @Test
  fun `the monolith client does not retry a request that timed out`() {
    FakeFlakyServer(stallFirstConnections = Int.MAX_VALUE).use { upstream ->
      val client = createMonolithHttpClient(createSharedHttpClient(), requestTimeoutMillis = 300)
      try {
        val outcome = runCatching { runBlocking { client.get(upstream.baseUrl) } }
        assert(outcome.exceptionOrNull() is HttpRequestTimeoutException)
        assert(upstream.connectionCount == 1)
      } finally {
        client.close()
      }
    }
  }

  /** OkHttp's default dispatcher lets five requests per host through and queues the rest; a webhook burst is larger. */
  @Test
  fun `the shared client keeps more than five requests to one host in flight at once`() {
    FakeFlakyServer(stallFirstConnections = Int.MAX_VALUE).use { upstream ->
      val client = createSharedHttpClient()
      try {
        runBlocking {
          val inFlight = (1..8).map { async(Dispatchers.IO) { runCatching { client.get(upstream.baseUrl) } } }
          val deadline = System.nanoTime() + 3_000_000_000L
          while (upstream.connectionCount < 8 && System.nanoTime() < deadline) delay(20)
          // Under the default limit the sixth to eighth request wait in the dispatcher until a timeout frees a slot.
          assert(upstream.connectionCount == 8)
          inFlight.forEach { it.cancel() }
        }
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
