package dropnext.dss.handler

import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import kotlin.test.Test


class ToDssErrorTest {

  @Test
  fun `what Shopify refused is the caller's problem`() {
    assert(ShopifyError.NotFound("missing").toDssError() is DssError.NotFound)
    assert(ShopifyError.UserError(listOf("qty", "tracking")).toDssError() == DssError.InvalidRequest("qty; tracking"))
  }

  @Test
  fun `what failed on the way is an upstream failure`() {
    assert(ShopifyError.GraphqlError("throttled").toDssError() is DssError.UpstreamFailure)
    assert(ShopifyError.HttpError(429).toDssError() is DssError.UpstreamFailure)
    assert(ShopifyError.Network("down").toDssError() is DssError.UpstreamFailure)
  }

  /** A retry cannot fix a revoked token, so it must not be filed under "upstream" where the monolith would retry it. */
  @Test
  fun `a rejected token is the shop's install problem`() {
    assert(ShopifyError.TokenRejected(401).toDssError() == DssError.ShopifyAdminTokenRejected(401))
  }

}
