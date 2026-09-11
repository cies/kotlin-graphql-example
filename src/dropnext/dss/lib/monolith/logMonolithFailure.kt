package dropnext.dss.lib.monolith

import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The one log line for a failed monolith call, so every failure carries the monolith's own trace
 * id and the two services' logs can be correlated. A 5xx, a transport failure and an unreadable
 * success are errors (the monolith is down, broken, or no longer speaks the contract); a 4xx is a
 * warning (we sent something it refuses).
 */
fun logMonolithFailure(
  operation: String,
  error: MonolithError,
  extra: String = "",
) {
  val suffix = if (extra.isBlank()) "" else " $extra"
  when (error) {
    is MonolithError.Transport ->
      log.error { "Monolith $operation failed: no response (${error.message})$suffix" }

    is MonolithError.Rejected -> {
      val line = "Monolith $operation failed: status=${error.status} ${error.body.formatForLog()}$suffix"
      if (error.status >= 500) log.error { line } else log.warn { line }
    }

    is MonolithError.Undecodable ->
      log.error { "Monolith $operation failed: status=${error.status} undecodable body (${error.detail})$suffix" }
  }
}
