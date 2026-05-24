package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.shopify.graphql.fulfillment.FulfillmentResult
import dropnext.dss.lib.shopify.graphql.fulfillment.toHttpStatus
import io.ktor.http.HttpStatusCode
import kotlin.test.Test

class FulfillmentHttpStatusTest {

  @Test
  fun `maps fulfillment errors to HTTP status codes`() {
    assert(FulfillmentResult.Err.NotFound("x").toHttpStatus() == HttpStatusCode.NotFound)
    assert(FulfillmentResult.Err.UserError(listOf("x")).toHttpStatus() == HttpStatusCode.BadRequest)
    assert(FulfillmentResult.Err.GraphqlError("x").toHttpStatus() == HttpStatusCode.BadGateway)
    assert(FulfillmentResult.Err.Network("x").toHttpStatus() == HttpStatusCode.BadGateway)
  }
}
