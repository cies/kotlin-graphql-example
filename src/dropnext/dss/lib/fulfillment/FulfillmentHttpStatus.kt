package dropnext.dss.lib.fulfillment

import io.ktor.http.HttpStatusCode

fun FulfillmentResult.Err.toHttpStatus(): HttpStatusCode =
  when (this) {
    is FulfillmentResult.Err.NotFound -> HttpStatusCode.NotFound
    is FulfillmentResult.Err.UserError -> HttpStatusCode.BadRequest
    is FulfillmentResult.Err.GraphqlError,
    is FulfillmentResult.Err.Network,
    -> HttpStatusCode.BadGateway
  }
