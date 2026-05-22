package dropnext.dss.lib.ktor

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import java.util.*
import org.slf4j.MDC


val DssTraceIdKey = AttributeKey<String>("DssTraceId")

fun Application.installDssTraceId() {
  intercept(ApplicationCallPipeline.Setup) {
    val traceId = call.request.header("X-Request-Id")?.trim()?.takeIf { it.isNotEmpty() }
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
