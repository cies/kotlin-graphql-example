package com.example.lib.shopify

import io.ktor.http.Parameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ShopifySignatures {
  fun verifyOAuthCallback(params: Parameters, clientSecret: String, hmacHex: String): Boolean {
    // Shopify: remove hmac/signature, sort by key, join with "&" as in query string (see OAuth docs).
    val message =
      params.entries()
        .asSequence()
        .filter { it.key != "hmac" && it.key != "signature" }
        .sortedBy { it.key }
        .joinToString("&") { e ->
          val value = e.value.singleOrNull() ?: e.value.firstOrNull().orEmpty()
          "${e.key}=$value"
        }
    val computedHex = hmacSha256Hex(clientSecret, message)
    return MessageDigest.isEqual(
      computedHex.lowercase().toByteArray(StandardCharsets.UTF_8),
      hmacHex.lowercase().toByteArray(StandardCharsets.UTF_8),
    )
  }

  fun verifyWebhook(hmacHeader: String?, clientSecret: String, rawBody: ByteArray): Boolean {
    if (hmacHeader.isNullOrBlank()) return false
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(clientSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(rawBody)
    runCatching {
      val decoded = Base64.getDecoder().decode(hmacHeader.trim())
      return MessageDigest.isEqual(digest, decoded)
    }
    return false
  }

  private fun hmacSha256Hex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { b ->
      "%02x".format(b)
    }
  }
}
