package dropnext.dss.testutil.helper

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/**
 * The two signatures Shopify sends us, computed the way Shopify computes them. Deliberately written
 * out with `javax.crypto` rather than reusing `dropnext.dss.lib.crypto.hmacSha256`: a test that
 * signs with the very function it verifies would still pass if both sides drifted together.
 */
fun base64HmacSha256(secret: String, body: ByteArray): String =
  java.util.Base64.getEncoder().encodeToString(rawHmacSha256(secret, body))

/** The OAuth callback's query-string signature, which Shopify sends as lowercase hex. */
fun hexHmacSha256(secret: String, message: String): String =
  rawHmacSha256(secret, message.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun rawHmacSha256(secret: String, body: ByteArray): ByteArray {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
  return mac.doFinal(body)
}
