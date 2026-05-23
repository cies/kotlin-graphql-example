package dropnext.dss.lib.ktor

import io.ktor.http.HttpStatusCode
import kotlin.test.Test

class DssErrorTest {

  @Test
  fun `invalid request maps to 400`() {
    assert(DssError.InvalidRequest("bad").toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `missing parameter renders structured message`() {
    val e = DssError.MissingParameter("shop")
    assert(e.message == "Missing shop")
    assert(e.toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `invalid parameter with detail interpolates detail`() {
    val e = DssError.InvalidParameter("shopify_subdomain", "not a valid host")
    assert(e.message == "Invalid shopify_subdomain: not a valid host")
    assert(e.toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `invalid parameter without detail uses generic message`() {
    val e = DssError.InvalidParameter("shopify_subdomain")
    assert(e.message == "Invalid shopify_subdomain")
  }

  @Test
  fun `unauthorized maps to 401 with default message`() {
    val e = DssError.Unauthorized()
    assert(e.message == "unauthorized")
    assert(e.toHttpStatus() == HttpStatusCode.Unauthorized)
  }

  @Test
  fun `missing shopify admin token maps to 401`() {
    val e = DssError.MissingShopifyAdminToken
    assert(e.toHttpStatus() == HttpStatusCode.Unauthorized)
    assert("X-Shopify-Access-Token" in e.message)
  }

  @Test
  fun `invalid signature maps to 403`() {
    assert(DssError.InvalidSignature("Invalid HMAC").toHttpStatus() == HttpStatusCode.Forbidden)
  }

  @Test
  fun `not found maps to 404`() {
    assert(DssError.NotFound("order 1 not found").toHttpStatus() == HttpStatusCode.NotFound)
  }

  @Test
  fun `upstream failure maps to 502`() {
    assert(DssError.UpstreamFailure("network down").toHttpStatus() == HttpStatusCode.BadGateway)
  }
}
