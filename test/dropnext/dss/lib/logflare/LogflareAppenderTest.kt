package dropnext.dss.lib.logflare

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import dropnext.dss.domain.LogflareApiKey
import dropnext.dss.lib.logging.TRACE_ID_MDC_KEY
import dropnext.dss.testutil.fake.FakeLogflareServer
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC


/**
 * The Logback half, which the monolith's copy of this appender cannot test: `logback-classic` is off
 * its test classpath. Here it is on the classpath, so these run the real path — a real logger, the
 * real appender, a real HTTP server — and pin the one thing the feature exists for: the request's
 * `trace_id` arrives at Logflare as a queryable field rather than as text inside the message.
 */
class LogflareAppenderTest {

  private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

  /** Logs [emit] through an appender pointed at [server], on a logger of its own so no other test sees it. */
  private fun shippedBy(server: FakeLogflareServer, emit: (LogbackLogger) -> Unit): List<JsonObject> {
    val appender = LogflareAppender().apply {
      sourceName = "dss-test"
      apiKey = LogflareApiKey("test-logflare-key")
      endpoint = server.endpoint
      flushInterval = 50.milliseconds
      context = loggerContext
    }
    val logger = loggerContext.getLogger("dropnext.dss.test.logflare")
    logger.addAppender(appender)
    logger.isAdditive = false // Keeps these lines out of the console the suite is printing to.
    appender.start()
    try {
      emit(logger)
      assert(server.awaitBatch())
      return server.receivedEvents
    } finally {
      appender.stop()
      logger.detachAppender(appender)
      logger.isAdditive = true
    }
  }

  @Test
  fun `ships the message with the level, the logger and the request's trace id`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        MDC.put(TRACE_ID_MDC_KEY, "trace-abc-123")
        try {
          logger.info("webhook accepted")
        } finally {
          MDC.remove(TRACE_ID_MDC_KEY)
        }
      }

      val metadata = events.single()["metadata"]!!.jsonObject
      assert(events.single()["message"]!!.jsonPrimitive.content == "webhook accepted")
      assert(metadata["level"]!!.jsonPrimitive.content == "INFO")
      assert(metadata["logger"]!!.jsonPrimitive.content == "dropnext.dss.test.logflare")
      // The whole point: correlating a DSS line with the monolith line it caused.
      assert(metadata[TRACE_ID_MDC_KEY]!!.jsonPrimitive.content == "trace-abc-123")
    }
  }

  @Test
  fun `ships the stack trace of a logged throwable`() {
    FakeLogflareServer().use { server ->
      server.knownSourceName = "dss-test"

      val events = shippedBy(server) { logger ->
        logger.warn("monolith rejected the order", IllegalStateException("boom"))
      }

      val error = events.single()["metadata"]!!.jsonObject["error"]!!.jsonPrimitive.content
      assert("IllegalStateException" in error)
      assert("boom" in error)
    }
  }

  @Test
  fun `refuses to start without a source name or an api key, and drops what is logged to it`() {
    FakeLogflareServer().use { server ->
      val appender = LogflareAppender().apply {
        apiKey = LogflareApiKey("test-logflare-key") // no sourceName
        endpoint = server.endpoint
        context = loggerContext
      }

      appender.start()

      // Not started, so `append` is a no-op rather than a queue that grows behind a shipper that
      // will never have a token to post under.
      assert(!appender.isStarted)
      assert(server.awaitNoBatch())
      assert(server.receivedEvents.isEmpty())
    }
  }
}
