package dropnext.dss.lib.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.generate


/** The header the trace id travels in: echoed on every response, forwarded on every monolith request. */
const val TRACE_ID_HEADER = "X-Trace-Id"

/**
 * The longest inbound trace id that is adopted. The header is anonymous input (the webhook endpoint
 * takes it from anyone), and an adopted id ends up in every log line of the request, in the response
 * and on the monolith's wire; a kilobyte of junk there is a nuisance, not a trace. A UUID is 36 characters.
 */
const val MAX_TRACE_ID_LENGTH = 64

/**
 * Gives every request a trace id — the caller's `X-Request-Id` or `X-Trace-Id` when present and
 * acceptable, a fresh one otherwise — and echoes it in the `X-Trace-Id` response header. Ktor also puts
 * it in the coroutine context, which is where the monolith client's `CallId` plugin reads it to forward
 * it and where `installCallLogging` copies it into the MDC for every log line of the request.
 */
fun Application.installCallId() {
  install(CallId) {
    retrieve { it.request.headers["X-Request-Id"]?.trim() }
    retrieve { it.request.headers[TRACE_ID_HEADER]?.trim() }
    generate(length = 16, dictionary = "0123456789abcdef")
    // Our own predicate rather than the plugin's default dictionary, which has no uppercase letters and
    // would silently replace an id the monolith minted. A failed check moves on to the next provider, so
    // an unacceptable inbound id is replaced by a generated one and never answered with a `400`.
    verify(::isAcceptableTraceId)
    replyToHeader(TRACE_ID_HEADER)
  }
}

/** Non-blank, at most [MAX_TRACE_ID_LENGTH] characters, and only what a log line and a header carry unharmed. */
internal fun isAcceptableTraceId(candidate: String): Boolean =
  candidate.isNotBlank() && candidate.length <= MAX_TRACE_ID_LENGTH && candidate.all { it.isTraceIdCharacter() }

// ASCII letters and digits plus the punctuation of a UUID, a path-like id and a namespaced one (`Monolith:Trace/42`).
private fun Char.isTraceIdCharacter(): Boolean =
  this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "-_.:/"
