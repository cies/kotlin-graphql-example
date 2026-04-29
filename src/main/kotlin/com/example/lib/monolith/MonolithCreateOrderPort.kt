package com.example.lib.monolith

import com.example.lib.dss.dto.CreateShopifyOrderRequest

/** Outbound create-order call to the main backend (monolith). */
fun interface MonolithCreateOrderPort {
  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): Result<HttpResponseSummary>
}

data class HttpResponseSummary(
  val status: Int,
  val body: String,
)
