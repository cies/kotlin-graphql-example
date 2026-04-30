package dropnext.dss.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import dropnext.dss.lib.monolith.CreateOrderResult
import dropnext.dss.lib.monolith.MonolithService


class FakeMonolithService : MonolithService {

  var failureMode = false

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult {
    if (failureMode) return CreateOrderResult.Error(500,"Simulated monolith failure")
    TODO("Not yet implemented")
  }
}
