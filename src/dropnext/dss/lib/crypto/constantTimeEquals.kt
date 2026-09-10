package dropnext.dss.lib.crypto

import java.security.MessageDigest


/**
 * Compares two byte arrays in constant time so callers cannot guess one from response-time
 * variation. Used for bearer secrets and HMAC digests — wherever a branch-on-mismatch would leak
 * bytes through timing.
 */
fun constantTimeEquals(expected: ByteArray, actual: ByteArray): Boolean = MessageDigest.isEqual(expected, actual)

fun constantTimeEquals(expected: String, actual: String): Boolean =
  constantTimeEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
