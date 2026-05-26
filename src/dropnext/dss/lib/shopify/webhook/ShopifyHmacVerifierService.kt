package dropnext.dss.lib.shopify.webhook

import io.ktor.http.Parameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/**
 * Verifies Shopify-issued HMAC signatures on inbound traffic. Binds the app's client secret
 * once at construction so callers don't thread it through every verification call.
 *
 * Stateless aside from the injected [clientSecret], so a single instance is safe to share
 * across requests.
 */
class ShopifyHmacVerifierService(private val clientSecret: String) {

  fun verifyOAuthCallback(params: Parameters, hmacHex: String): Boolean {
    // Shopify: remove hmac/signature, sort by key, join with "&" as in query string (see OAuth docs).
    val message = params.entries()
      .asSequence()
      .filter { it.key != "hmac" && it.key != "signature" }
      .sortedBy { it.key }
      .joinToString("&") { e ->
        val value = e.value.singleOrNull() ?: e.value.firstOrNull().orEmpty()
        "${e.key}=$value"
      }
    val providedBytes = hexToBytesOrNull(hmacHex) ?: return false
    val computedBytes = hmacSha256(clientSecret, message)
    return MessageDigest.isEqual(computedBytes, providedBytes)
  }

  fun verifyWebhook(hmacHeader: String?, rawBody: ByteArray): Boolean {
    if (hmacHeader.isNullOrBlank()) return false
    val decoded = try {
      Base64.getDecoder().decode(hmacHeader.trim())
    } catch (_: IllegalArgumentException) {
      return false
    }
    val digest = hmacSha256(clientSecret, rawBody)
    return MessageDigest.isEqual(digest, decoded)
  }

  private fun hmacSha256(secret: String, message: String): ByteArray =
    hmacSha256(secret, message.toByteArray(StandardCharsets.UTF_8))

  private fun hmacSha256(secret: String, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message)
  }

  /** Decodes an ASCII hex string to bytes, returning `null` if it is malformed. */
  private fun hexToBytesOrNull(hex: String): ByteArray? {
    val s = hex.trim()
    if (s.length % 2 != 0 || s.isEmpty()) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
      val hi = Character.digit(s[i * 2], 16)
      val lo = Character.digit(s[i * 2 + 1], 16)
      if (hi < 0 || lo < 0) return null
      out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
  }
}
