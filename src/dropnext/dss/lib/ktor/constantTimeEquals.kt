package dropnext.dss.lib.ktor

import java.security.MessageDigest


/**
 * Compares two strings in constant time so callers cannot guess one from response-time
 * variation. Used for `X-DSS-Internal-Secret` and similar shared secrets — wherever a
 * branch-on-mismatch would leak bytes through timing.
 */
internal fun constantTimeEquals(expected: String, actual: String): Boolean {
  val a = expected.toByteArray(Charsets.UTF_8)
  val b = actual.toByteArray(Charsets.UTF_8)
  return MessageDigest.isEqual(a, b)
}
