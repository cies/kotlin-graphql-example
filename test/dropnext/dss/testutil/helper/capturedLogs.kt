package dropnext.dss.testutil.helper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/** The lock name a test declares when it attaches to the root logger, which every test shares. */
const val GLOBAL_LOG_REGISTRY = "global-log-registry"

/**
 * Every line logged anywhere while [block] runs, as `LEVEL message` plus the MDC. Attaches a real
 * Logback appender to the root logger rather than reading a captured stream, so it sees exactly what
 * an appender shipping to Logflare would see.
 *
 * The root logger is global: a test using this declares `@ResourceLock(GLOBAL_LOG_REGISTRY)` so it
 * stays correct once test classes run concurrently.
 */
fun capturingLogs(block: () -> Unit): List<String> {
  val recorded = CopyOnWriteArrayList<String>()
  val appender = object : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
      val mdc = event.mdcPropertyMap.entries.joinToString(",") { "${it.key}=${it.value}" }
      recorded += "${event.level} ${event.formattedMessage} {$mdc}"
      event.throwableProxy?.let { recorded += "${event.level} THROWN ${it.className}: ${it.message}" }
    }
  }
  val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
  val originalLevel = root.level
  appender.context = root.loggerContext
  appender.start()
  root.addAppender(appender)
  root.level = Level.TRACE
  try {
    block()
  } finally {
    root.level = originalLevel
    root.detachAppender(appender)
    appender.stop()
  }
  return recorded.toList()
}
