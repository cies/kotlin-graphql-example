package dropnext.dss.lib.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.generate


/** The header the trace id travels in: echoed on every response, forwarded on every monolith request. */
const val TRACE_ID_HEADER = "X-Trace-Id"

/**
 * Gives every request a trace id — the caller's `X-Request-Id` or `X-Trace-Id` when present, a fresh one
 * otherwise — and echoes it in the `X-Trace-Id` response header. Ktor also puts it in the coroutine
 * context, which is where the monolith client's `CallId` plugin reads it to forward it and where
 * `installCallLogging` copies it into the MDC for every log line of the request.
 */
fun Application.installCallId() {
  install(CallId) {
    retrieve { it.request.headers["X-Request-Id"]?.trim() }
    retrieve { it.request.headers[TRACE_ID_HEADER]?.trim() }
    generate(length = 16, dictionary = "0123456789abcdef")
    // The plugin's default dictionary has no uppercase letters: an id the monolith minted would be silently replaced.
    verify { it.isNotBlank() }
    replyToHeader(TRACE_ID_HEADER)
  }
}
