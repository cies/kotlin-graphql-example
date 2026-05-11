package dropnext.dss

import dropnext.dss.shopify.normalizeShopDomain
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Simple file-backed store for shop→token pairs used during local testing.
 * Each line in the file is `shop.myshopify.com|shpat_xxx`; blank lines and lines
 * starting with `#` are ignored.
 *
 * [saveToken] is safe to call from a coroutine context: the write is done to a temp
 * file and then atomically renamed, so a concurrent read never sees a partial file.
 */
class TokenFileStore(private val filePath: String) {

  fun loadTokens(): Map<String, String> {
    val file = File(filePath)
    if (!file.exists()) return emptyMap()
    return file.readLines()
      .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
        val idx = trimmed.indexOf('|')
        if (idx <= 0 || idx == trimmed.length - 1) return@mapNotNull null
        val shopRaw = trimmed.substring(0, idx).trim()
        val token = trimmed.substring(idx + 1).trim()
        val host = normalizeShopDomain(shopRaw) ?: return@mapNotNull null
        host.lowercase() to token
      }.toMap()
  }

  @Synchronized
  fun saveToken(shop: String, token: String) {
    val file = File(filePath)
    val existing: Map<String, String> = if (file.exists()) loadTokens() else emptyMap()
    val updated = existing.toMutableMap()
    updated[shop.lowercase()] = token

    val lines = updated.entries.joinToString("\n") { (s, t) -> "$s|$t" }
    val tmp = File("$filePath.tmp")
    tmp.writeText(lines + "\n")
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }
}
