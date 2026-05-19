package dropnext.dss.shopify

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val OAUTH_STATE_TTL_SECONDS = 300L

fun signedOAuthState(shop: String, clientSecret: String, now: Instant = Instant.now()): String {
  val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
  val noncePart = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
  val expiresAt = now.epochSecond + OAUTH_STATE_TTL_SECONDS
  val payload = "$shop|$expiresAt|$noncePart"
  val signature = hmacSha256Base64Url(clientSecret, payload)
  return "$payload|$signature"
}

fun isValidSignedOAuthState(
  state: String,
  expectedShop: String,
  clientSecret: String,
  now: Instant = Instant.now(),
): Boolean {
  val parts = state.split('|')
  if (parts.size != 4) return false
  val shop = parts[0]
  val expiresAt = parts[1].toLongOrNull() ?: return false
  val nonce = parts[2]
  val signature = parts[3]
  if (shop != expectedShop) return false
  if (expiresAt < now.epochSecond) return false
  val payload = "$shop|$expiresAt|$nonce"
  val expectedSignature = hmacSha256Base64Url(clientSecret, payload)
  return MessageDigest.isEqual(
    expectedSignature.toByteArray(StandardCharsets.UTF_8),
    signature.toByteArray(StandardCharsets.UTF_8),
  )
}

private fun hmacSha256Base64Url(secret: String, message: String): String {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
  return Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)))
}
