package dropnext.dss

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import org.slf4j.MDC
import java.util.UUID

val DssTraceIdKey = AttributeKey<String>("DssTraceId")

fun Application.installDssTraceId() {
  intercept(ApplicationCallPipeline.Setup) {
    val traceId =
      call.request.header("X-Request-Id")?.trim()?.takeIf { it.isNotEmpty() }
        ?: call.request.header("X-Trace-Id")?.trim()?.takeIf { it.isNotEmpty() }
        ?: UUID.randomUUID().toString().replace("-", "").take(16)
    call.attributes.put(DssTraceIdKey, traceId)
    MDC.put("trace_id", traceId)
    try {
      proceed()
    } finally {
      call.response.headers.append("X-Trace-Id", traceId)
      MDC.remove("trace_id")
    }
  }
}

fun ApplicationCall.dssTraceId(): String =
  attributes.getOrNull(DssTraceIdKey) ?: "unknown"
