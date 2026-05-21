package dropnext.dss.lib.dss

import io.ktor.http.HttpStatusCode

fun FulfillmentResult.Err.toHttpStatus(): HttpStatusCode =
  when (this) {
    is FulfillmentResult.Err.NotFound -> HttpStatusCode.NotFound
    is FulfillmentResult.Err.UserError -> HttpStatusCode.BadRequest
    is FulfillmentResult.Err.GraphQlError,
    is FulfillmentResult.Err.Network,
    -> HttpStatusCode.BadGateway
  }
