package dropnext.dss.lib.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/** The one HMAC-SHA256 the OAuth state, the OAuth callback and the webhook bodies are all signed with. */
fun hmacSha256(secret: String, message: ByteArray): ByteArray {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
  return mac.doFinal(message)
}

fun hmacSha256(secret: String, message: String): ByteArray = hmacSha256(secret, message.toByteArray(Charsets.UTF_8))
