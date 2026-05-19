package dropnext.dss.lib.monolith

import org.slf4j.Logger

fun logMonolithFailure(
  log: Logger,
  operation: String,
  status: Int,
  parsed: MonolithErrorBody?,
  extra: String = "",
) {
  val suffix = if (extra.isBlank()) "" else " $extra"
  val detail = parsed?.formatForLog() ?: "monolith_error=unparsed"
  log.warn("Monolith $operation failed: status=$status $detail$suffix")
}
