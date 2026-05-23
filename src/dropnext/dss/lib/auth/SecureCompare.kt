package dropnext.dss.lib.auth

import java.security.MessageDigest

/**
 * Compares two strings in **constant time** so callers cannot guess the secret from timing.
 * Use for `X-DSS-Internal-Secret` and similar shared secrets.
 */
fun constantTimeEquals(expected: String, actual: String): Boolean {
  val a = expected.toByteArray(Charsets.UTF_8)
  val b = actual.toByteArray(Charsets.UTF_8)
  return MessageDigest.isEqual(a, b)
}
