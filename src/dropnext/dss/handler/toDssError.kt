package dropnext.dss.handler

import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError


/**
 * Maps a Shopify failure onto the HTTP answer: what Shopify refused is the caller's problem, a
 * refused token is ours to fix by reinstalling, what failed on the way is upstream.
 */
fun ShopifyError.toDssError(): DssError = when (this) {
  is ShopifyError.NotFound -> DssError.NotFound(message)
  is ShopifyError.UserError -> DssError.InvalidRequest(message)
  is ShopifyError.TokenRejected -> DssError.ShopifyAdminTokenRejected(httpStatus)
  is ShopifyError.GraphqlError -> DssError.UpstreamFailure(message)
  is ShopifyError.HttpError -> DssError.UpstreamFailure(message)
  is ShopifyError.Network -> DssError.UpstreamFailure(message)
}

