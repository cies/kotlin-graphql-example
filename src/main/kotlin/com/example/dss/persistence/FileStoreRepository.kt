package com.example.dss.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileStoreRepository(
  private val dataDir: Path,
) {
  private val json =
    Json {
      prettyPrint = true
      ignoreUnknownKeys = true
    }

  private val file: Path
    get() = dataDir.resolve("stores.json")

  /** Single lock for all read–modify–write so two requests cannot interleave and lose updates. */
  private val lock = Any()

  private fun readStoresFile(): StoresFile {
    if (!Files.isRegularFile(file)) {
      Files.createDirectories(dataDir)
      val empty = StoresFile()
      writeStoresFileAtomic(empty)
      return empty
    }
    val text = Files.readString(file)
    return runCatching { json.decodeFromString(StoresFile.serializer(), text) }.getOrElse { StoresFile() }
  }

  /**
   * Writes JSON via a temp file + atomic replace so a crash mid-write does not corrupt `stores.json`.
   */
  private fun writeStoresFileAtomic(data: StoresFile) {
    Files.createDirectories(dataDir)
    val text = json.encodeToString(StoresFile.serializer(), data)
    val tmp = Files.createTempFile(dataDir, "stores", ".tmp")
    try {
      Files.writeString(tmp, text)
      try {
        Files.move(
          tmp,
          file,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (_: Exception) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  fun load(): StoresFile = synchronized(lock) { readStoresFile() }

  fun findBySubdomain(subdomain: String): StoreRecord? =
    synchronized(lock) {
      readStoresFile().stores.find { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
    }

  fun getAccessToken(subdomain: String): String? = findBySubdomain(subdomain)?.accessToken

  fun upsert(
    subdomain: String,
    shopifyShopId: Long,
    accessToken: String,
  ): StoreRecord =
    synchronized(lock) {
      val data = readStoresFile()
      val existing = data.stores.indexOfFirst { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
      if (existing >= 0) {
        val old = data.stores[existing]
        val record = old.copy(shopifyShopId = shopifyShopId, accessToken = accessToken)
        val newList = data.stores.toMutableList().also { it[existing] = record }
        writeStoresFileAtomic(data.copy(stores = newList))
        return record
      }
      val id = data.nextStoreId
      val record =
        StoreRecord(
          id = id,
          shopifySubdomain = subdomain.lowercase(),
          shopifyShopId = shopifyShopId,
          accessToken = accessToken,
        )
      writeStoresFileAtomic(data.copy(stores = data.stores + record, nextStoreId = data.nextStoreId + 1))
      return record
    }

  /** Reject if existing shop id differs (subdomain collision protection per OpenAPI). */
  fun updateApiKeyIfShopMatches(
    subdomain: String,
    shopifyShopId: Long,
    accessToken: String,
  ): Result<StoreRecord> =
    synchronized(lock) {
      val data = readStoresFile()
      val idx = data.stores.indexOfFirst { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
      if (idx < 0) {
        val id = data.nextStoreId++
        val record =
          StoreRecord(
            id = id,
            shopifySubdomain = subdomain.lowercase(),
            shopifyShopId = shopifyShopId,
            accessToken = accessToken,
          )
        writeStoresFileAtomic(data.copy(stores = data.stores + record, nextStoreId = data.nextStoreId))
        return Result.success(record)
      }
      val current = data.stores[idx]
      if (current.shopifyShopId != 0L && current.shopifyShopId != shopifyShopId) {
        return Result.failure(IllegalStateException("shop id mismatch"))
      }
      val updated = current.copy(shopifyShopId = shopifyShopId, accessToken = accessToken)
      val newStores = data.stores.toMutableList().also { it[idx] = updated }
      writeStoresFileAtomic(data.copy(stores = newStores))
      return Result.success(updated)
    }

  /**
   * Only updates token + shop id if we **already** saved that subdomain.
   * Used by `PUT /stores/api-key`. New installs use [upsert] instead, so this returns “not found”
   * if there is no row yet.
   */
  fun updateApiKeyForExistingStoreOnly(
    subdomain: String,
    shopifyShopId: Long,
    accessToken: String,
  ): Result<StoreRecord> =
    synchronized(lock) {
      val data = readStoresFile()
      val idx = data.stores.indexOfFirst { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
      if (idx < 0) {
        return Result.failure(IllegalStateException("store not found"))
      }
      val current = data.stores[idx]
      if (current.shopifyShopId != 0L && current.shopifyShopId != shopifyShopId) {
        return Result.failure(IllegalStateException("shop id mismatch"))
      }
      val updated = current.copy(shopifyShopId = shopifyShopId, accessToken = accessToken)
      val newStores = data.stores.toMutableList().also { it[idx] = updated }
      writeStoresFileAtomic(data.copy(stores = newStores))
      return Result.success(updated)
    }

  fun mergeVariantIds(subdomain: String, ids: Collection<Long>) =
    synchronized(lock) {
      val data = readStoresFile()
      val idx = data.stores.indexOfFirst { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
      if (idx < 0) return
      val s = data.stores[idx]
      val merged = s.copy(productVariantIds = s.productVariantIds + ids)
      val newStores = data.stores.toMutableList().also { it[idx] = merged }
      writeStoresFileAtomic(data.copy(stores = newStores))
    }

  fun removeVariantIds(subdomain: String, ids: Collection<Long>) =
    synchronized(lock) {
      val data = readStoresFile()
      val idx = data.stores.indexOfFirst { it.shopifySubdomain.equals(subdomain, ignoreCase = true) }
      if (idx < 0) return
      val s = data.stores[idx]
      val merged = s.copy(productVariantIds = s.productVariantIds - ids.toSet())
      val newStores = data.stores.toMutableList().also { it[idx] = merged }
      writeStoresFileAtomic(data.copy(stores = newStores))
    }
}
