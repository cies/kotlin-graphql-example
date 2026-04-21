package com.example.dss.persistence

import kotlinx.serialization.Serializable

@Serializable
data class StoreRecord(
  val id: Long,
  val shopifySubdomain: String,
  val shopifyShopId: Long,
  val accessToken: String,
  /** Known variant ids for catalog diff (monolith / DSS). */
  val productVariantIds: Set<Long> = emptySet(),
)

@Serializable
data class StoresFile(
  val stores: List<StoreRecord> = emptyList(),
  var nextStoreId: Long = 1L,
)
