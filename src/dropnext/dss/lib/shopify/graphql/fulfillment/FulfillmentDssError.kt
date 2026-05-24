package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.ktor.DssError

/** Maps a [Err] onto the central [DssError] taxonomy used by handlers. */
fun FulfillmentResult.Err.toDssError(): DssError = when (this) {
  is FulfillmentResult.Err.NotFound -> DssError.NotFound(detail)
  is FulfillmentResult.Err.UserError -> DssError.InvalidRequest(messages.joinToString("; "))
  is FulfillmentResult.Err.GraphqlError -> DssError.UpstreamFailure(raw)
  is FulfillmentResult.Err.Network -> DssError.UpstreamFailure(message)
}
