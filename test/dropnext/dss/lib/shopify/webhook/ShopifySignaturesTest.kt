package dropnext.dss.lib.shopify.webhook

import dropnext.dss.lib.shopify.webhook.ShopifySignatures
import io.ktor.http.parametersOf
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test

class ShopifySignaturesTest {

  private val secret = "shpss_test_secret"

  @Test
  fun `verifyWebhook accepts a correct base64 hmac`() {
    val body = """{"id":1001,"name":"#1001"}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(secret, body)
    assert(ShopifySignatures.verifyWebhook(hmac, secret, body))
  }

  @Test
  fun `verifyWebhook rejects a tampered body`() {
    val body = """{"id":1001}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(secret, body)
    val tampered = """{"id":1002}""".toByteArray(StandardCharsets.UTF_8)
    assert(!ShopifySignatures.verifyWebhook(hmac, secret, tampered))
  }

  @Test
  fun `verifyWebhook rejects wrong secret`() {
    val body = """{"id":1}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256("other-secret", body)
    assert(!ShopifySignatures.verifyWebhook(hmac, secret, body))
  }

  @Test
  fun `verifyWebhook rejects null or blank hmac header`() {
    val body = """{"x":1}""".toByteArray(StandardCharsets.UTF_8)
    assert(!ShopifySignatures.verifyWebhook(null, secret, body))
    assert(!ShopifySignatures.verifyWebhook("", secret, body))
    assert(!ShopifySignatures.verifyWebhook("   ", secret, body))
  }

  @Test
  fun `verifyWebhook rejects malformed base64`() {
    val body = """{"x":1}""".toByteArray(StandardCharsets.UTF_8)
    assert(!ShopifySignatures.verifyWebhook("!!!not base64!!!", secret, body))
  }

  @Test
  fun `verifyOAuthCallback accepts a correctly-signed query`() {
    val params = parametersOf(
      "shop" to listOf("acme.myshopify.com"),
      "code" to listOf("abc123"),
      "timestamp" to listOf("1700000000"),
    )
    val expectedMessage = "code=abc123&shop=acme.myshopify.com&timestamp=1700000000"
    val hmac = hexHmacSha256(secret, expectedMessage)
    assert(ShopifySignatures.verifyOAuthCallback(params, secret, hmac))
  }

  @Test
  fun `verifyOAuthCallback excludes hmac and signature params from the canonical string`() {
    val params = parametersOf(
      "shop" to listOf("acme.myshopify.com"),
      "code" to listOf("abc"),
      "hmac" to listOf("ignored"),
      "signature" to listOf("ignored-too"),
    )
    val canonical = "code=abc&shop=acme.myshopify.com"
    val hmac = hexHmacSha256(secret, canonical)
    assert(ShopifySignatures.verifyOAuthCallback(params, secret, hmac))
  }

  @Test
  fun `verifyOAuthCallback rejects a wrong hmac`() {
    val params = parametersOf("shop" to listOf("acme.myshopify.com"))
    assert(!ShopifySignatures.verifyOAuthCallback(params, secret, "00".repeat(32)))
  }

  @Test
  fun `verifyOAuthCallback hmac matching is case insensitive`() {
    val params = parametersOf("shop" to listOf("acme.myshopify.com"))
    val canonical = "shop=acme.myshopify.com"
    val lower = hexHmacSha256(secret, canonical)
    val upper = lower.uppercase()
    assert(ShopifySignatures.verifyOAuthCallback(params, secret, upper))
  }

  private fun base64HmacSha256(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(body))
  }

  private fun hexHmacSha256(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
