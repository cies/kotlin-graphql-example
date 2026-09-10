package dropnext.dss.lib.logflare

import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.testutil.fake.FakeLogflareServer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


/**
 * The shipping half of [LogflareAppender], against a real HTTP server on loopback: a fake client
 * would prove the calls were built, not that Logflare's answers are handled, and every interesting
 * failure here is an HTTP one — a non-2xx handshake, a rejected batch, an endpoint that never answers.
 *
 * Ported from the monolith, where this split exists because `logback-classic` is off that project's
 * test classpath and the appender cannot be constructed at all. Here it can, and [LogflareAppenderTest]
 * covers it; the split is kept so a fix on either side still applies to the other.
 */
class LogflareBatchSenderTest {

  private val errors = ConcurrentLinkedQueue<String>()

  private fun senderFor(
    server: FakeLogflareServer,
    maxBatchSize: Int = 50,
    maxQueuedEvents: Int = 10_000,
    flushInterval: Duration = 50.milliseconds,
  ) = LogflareBatchSender(
    endpoint = server.endpoint,
    apiKey = LogflareApiKey("test-logflare-key"),
    maxBatchSize = maxBatchSize,
    maxQueuedEvents = maxQueuedEvents,
    flushInterval = flushInterval,
    reportError = { errors += it },
  )

  private fun entry(message: String): JsonObject = buildJsonObject { put("message", message) }

  private fun messagesOf(server: FakeLogflareServer) =
    server.receivedEvents.map { it["message"]!!.jsonPrimitive.content }

  @Test
  fun `resolves the token of a source Logflare already knows`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dropnext-test"
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("dropnext-test")

      assert(token == "token-for-dropnext-test")
      assert(sender.sourceToken == "token-for-dropnext-test")
      assert(errors.isEmpty())
    }
  }

  @Test
  fun `creates the source when Logflare does not have it yet`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("brand-new-source")

      assert(server.createdSourceNames == listOf("brand-new-source"))
      assert(token == "token-for-brand-new-source")
    }
  }

  @Test
  fun `reports and refuses to resolve a token when the sources call fails`() {
    FakeLogflareServer().use { server ->
      server.sourcesStatusCode = 500
      val sender = senderFor(server)

      val token = sender.resolveSourceToken("dropnext-test")

      // Null is what tells the appender not to start at all — a started appender with no token
      // would post every batch to `?source=` and lose the lot.
      assert(token == null)
      assert(sender.sourceToken == "")
      assert(errors.any { "500" in it })
    }
  }

  @Test
  fun `the scheduled flush ships what was queued`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server)
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      sender.enqueue(entry("first"))
      sender.enqueue(entry("second"))

      assert(server.awaitBatch())
      sender.close()
      assert(messagesOf(server) == listOf("first", "second"))
    }
  }

  @Test
  fun `starting with a source name resolves the token on the flush thread and ships what queued meanwhile`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dropnext-test"
      val sender = senderFor(server)

      // No `resolveSourceToken` here: logging is available from `start` on, and the handshake — two
      // blocking HTTP calls — happens on the flush thread instead of the booting one.
      sender.start("dropnext-test")
      sender.enqueue(entry("logged before the handshake finished"))

      assert(server.awaitBatch())
      sender.close()
      assert(messagesOf(server) == listOf("logged before the handshake finished"))
      assert(sender.sourceToken == "token-for-dropnext-test")
    }
  }

  @Test
  fun `a failed handshake drops events instead of posting them without a source`() {
    FakeLogflareServer().use { server ->
      server.sourcesStatusCode = 500
      val sender = senderFor(server)

      sender.start("dropnext-test")
      // Give the flush thread its handshake attempt and a flush cycle or two.
      assert(server.awaitNoBatch())
      sender.enqueue(entry("nowhere to go"))
      sender.close()

      // Posting to `?source=` would lose the batch anyway, and holding it would grow the heap for
      // as long as the process runs.
      assert(messagesOf(server).isEmpty())
      assert(sender.queuedEventCount == 0)
      assert(errors.any { "500" in it })
    }
  }

  @Test
  fun `a flush ships at most one batch and leaves the rest queued`() {
    FakeLogflareServer().use { server ->
      // No schedule started, so `flush` is the only thing that drains: exactly one batch's worth.
      val sender = senderFor(server, maxBatchSize = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      (1..5).forEach { sender.enqueue(entry("event-$it")) }

      sender.flush()

      assert(messagesOf(server) == listOf("event-1", "event-2"))
      assert(sender.queuedEventCount == 3)
    }
  }

  @Test
  fun `closing ships what is still queued`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()
      sender.enqueue(entry("last words"))

      sender.close()

      // The interval is a minute, so nothing but the stop-flush could have shipped this.
      assert(messagesOf(server) == listOf("last words"))
      assert(sender.queuedEventCount == 0)
    }
  }

  @Test
  fun `closing drains more than one batch`() {
    FakeLogflareServer().use { server ->
      // One `flush` ships `maxBatchSize`; without a drain loop `close` would leave 6 of these behind.
      val sender = senderFor(server, maxBatchSize = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()
      (1..8).forEach { sender.enqueue(entry("event-$it")) }

      sender.close()

      assert(sender.queuedEventCount == 0)
      assert(messagesOf(server).size == 8)
    }
  }

  /**
   * The drop path used to submit a flush task per dropped event onto the scheduled executor's
   * *unbounded* queue, which reintroduced the heap growth the bounded event queue exists to prevent.
   */
  @Test
  fun `overflow does not schedule work per dropped event`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, maxQueuedEvents = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.start()

      (1..5_000).forEach { sender.enqueue(entry("event-$it")) }

      // Nothing was scheduled, so nothing drained on its own: the queue still holds exactly its cap.
      assert(sender.queuedEventCount == 2)
      assert(messagesOf(server).isEmpty())
      sender.close()
    }
  }

  @Test
  fun `a full queue drops events instead of growing without limit`() {
    FakeLogflareServer().use { server ->
      // Nothing draining: a long Logflare outage looks exactly like this from the queue's side.
      val sender = senderFor(server, maxQueuedEvents = 3, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")

      (1..10).forEach { sender.enqueue(entry("event-$it")) }

      assert(sender.queuedEventCount == 3)
      // The oldest three survive: dropping the newest keeps the enqueueing thread from ever blocking,
      // which is the point — a logging call must not park the request thread that made it.
      sender.flush()
      assert(messagesOf(server) == listOf("event-1", "event-2", "event-3"))
    }
  }

  @Test
  fun `the next flush reports how many events were dropped`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, maxQueuedEvents = 2, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      (1..5).forEach { sender.enqueue(entry("event-$it")) }

      sender.flush()

      // Silent loss would be the worst version of this: an operator reading the logs cannot tell a
      // gap from a quiet period.
      assert(errors.any { "dropped 3 log events" in it })
      // And the count resets, or every later flush re-reports the same drops.
      errors.clear()
      sender.flush()
      assert(errors.none { "dropped" in it })
    }
  }

  @Test
  fun `a rejected batch is reported rather than thrown`() {
    FakeLogflareServer().use { server ->
      server.logsStatusCode = 503
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.enqueue(entry("into the void"))

      sender.flush()

      // A throw here would surface inside whatever thread called `log.info { }`.
      assert(errors.any { "Logflare flush failed: 503" in it })
    }
  }

  @Test
  fun `an unreachable endpoint is reported rather than thrown`() {
    // Port 1 on loopback: nothing listens, so the connect fails rather than the request.
    val sender = LogflareBatchSender(
      endpoint = "http://127.0.0.1:1",
      apiKey = LogflareApiKey("test-logflare-key"),
      flushInterval = 1.minutes,
      reportError = { errors += it },
    )

    assert(sender.resolveSourceToken("dropnext-test") == null)
    assert(errors.any { "/api/sources" in it })
  }

  @Test
  fun `every request carries the api key`() {
    FakeLogflareServer().use { server ->
      val sender = senderFor(server, flushInterval = 1.minutes)
      sender.resolveSourceToken("dropnext-test")
      sender.enqueue(entry("first"))

      sender.flush()

      // Two shapes, deliberately: Logflare wants a bearer token on the management API and a raw
      // `X-API-KEY` on the ingest one, and sending the wrong one is a silent 401 in production.
      assert("Bearer test-logflare-key" in server.receivedApiKeys)
      assert("test-logflare-key" in server.receivedApiKeys)
    }
  }
}
