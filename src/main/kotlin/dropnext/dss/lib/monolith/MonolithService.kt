package dropnext.dss.lib.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest


/** Outbound create-order call to the main backend (monolith). */
interface MonolithService {
  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult
}

sealed interface CreateOrderResult {
  data class HttpResponseSummary(
    val status: Int,
    val body: String,
  ) : CreateOrderResult

  data class Error(
    val status: Int,
    val errorMessage: String,
  ) : CreateOrderResult
}

