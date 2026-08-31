package dropnext.dss.workflow

import dropnext.dss.lib.ktor.DssError
import kotlin.test.Test


class ShopifyMutationTest {

  @Test
  fun `determine errors map onto DssError statuses`() {
    assert(DetermineShopifyMutationsError.NotFound("missing").toDssError() is DssError.NotFound)
    assert(DetermineShopifyMutationsError.UserError(listOf("qty")).toDssError() is DssError.InvalidRequest)
    assert(DetermineShopifyMutationsError.GraphqlError("throttled").toDssError() is DssError.UpstreamFailure)
    assert(DetermineShopifyMutationsError.Network("down").toDssError() is DssError.UpstreamFailure)
  }

  @Test
  fun `effect errors map onto DssError statuses`() {
    assert(ShopifyError.NotFound("missing").toDssError() is DssError.NotFound)
    assert(ShopifyError.UserError(listOf("qty")).toDssError() is DssError.InvalidRequest)
    assert(ShopifyError.GraphqlError("throttled").toDssError() is DssError.UpstreamFailure)
    assert(ShopifyError.Network("down").toDssError() is DssError.UpstreamFailure)
  }

  @Test
  fun `empty effect errors means success`() {
    val result = EffectShopifyMutationsResult(newFulfillmentIds = listOf(1L), errors = emptyList())
    assert(result.errors.isEmpty())
    assert(result.newFulfillmentIds == listOf(1L))
  }
}
