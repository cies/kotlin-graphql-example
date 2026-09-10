package dropnext.dss.lib.monolith

import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The one log line for a failed monolith call, so every failure carries the monolith's own trace
 * id and the two services' logs can be correlated. A 5xx and a transport failure are errors (the
 * monolith is down or broken); a 4xx is a warning (we sent something it refuses).
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
  }
}
