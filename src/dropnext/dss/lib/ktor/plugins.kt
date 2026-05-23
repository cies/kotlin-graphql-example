package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import java.util.UUID
import org.slf4j.MDC


private val log = KotlinLogging.logger {}

/** Catches anything that escapes a handler — returns a generic 500 so secrets never leak into the body. */
fun Application.installDssStatusPages() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      log.error(cause) { "Unhandled error on ${call.request.local.method.value} ${call.request.local.uri}" }
      call.respondErrorText("internal error")
    }
  }
}

/** Installs `ContentNegotiation` with the shared inbound [AppJson] config. */
fun Application.installJsonContentNegotiation() {
  install(ContentNegotiation) {
    json(AppJson)
  }
}

// TODO(cies): values in lowercase start, right?
val dssTraceIdKey = AttributeKey<String>("DssTraceId")

fun Application.installDssTraceId() {
  intercept(ApplicationCallPipeline.Setup) {
    val traceId = call.request.header("X-Request-Id")?.trim()?.takeIf { it.isNotEmpty() }
      ?: call.request.header("X-Trace-Id")?.trim()?.takeIf { it.isNotEmpty() }
      ?: UUID.randomUUID().toString().replace("-", "").take(16)
    call.attributes.put(dssTraceIdKey, traceId)
    // Append the response header before proceed() so it is in place before the route commits the response.
    call.response.headers.append("X-Trace-Id", traceId)
    MDC.put("trace_id", traceId)
    try {
      proceed()
    } finally {
      MDC.remove("trace_id")
    }
  }
}
