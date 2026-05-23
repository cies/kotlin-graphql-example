package dropnext.dss.lib.fulfillment

import dropnext.dss.lib.ktor.DssError
import io.ktor.http.HttpStatusCode

/** Maps a [FulfillmentResult.Err] onto the central [DssError] taxonomy used by handlers. */
fun FulfillmentResult.Err.toDssError(): DssError = when (this) {
  is FulfillmentResult.Err.NotFound -> DssError.NotFound(detail)
  is FulfillmentResult.Err.UserError -> DssError.InvalidRequest(messages.joinToString("; "))
  is FulfillmentResult.Err.GraphqlError -> DssError.UpstreamFailure(raw)
  is FulfillmentResult.Err.Network -> DssError.UpstreamFailure(message)
}

/** Kept for callers that already work with [HttpStatusCode]; new code should map via [toDssError]. */
fun FulfillmentResult.Err.toHttpStatus(): HttpStatusCode = when (this) {
  is FulfillmentResult.Err.NotFound -> HttpStatusCode.NotFound
  is FulfillmentResult.Err.UserError -> HttpStatusCode.BadRequest
  is FulfillmentResult.Err.GraphqlError,
  is FulfillmentResult.Err.Network,
  -> HttpStatusCode.BadGateway
}
