package dropnext.dss.lib.ktor

import io.ktor.http.HttpStatusCode
import kotlin.test.Test

/**
 * Tests for the pure (text, status) helpers. The thin Ktor wrappers that call
 * [io.ktor.server.response.respondText] are deliberately not tested here; they have
 * no logic worth exercising and Ktor itself covers the response plumbing.
 *
 * Header-reading and secret-checking extensions still need an HTTP server — those
 * are exercised end-to-end by the handler tests via `respondBadRequestText` calls.
 */
class ExtensionsTest {

  @Test
  fun `badRequestText pairs text with 400`() {
    val r = badRequestText("oops")
    assert(r.text == "oops")
    assert(r.status == HttpStatusCode.BadRequest)
  }

  @Test
  fun `forbiddenText pairs text with 403`() {
    val r = forbiddenText("nope")
    assert(r.text == "nope")
    assert(r.status == HttpStatusCode.Forbidden)
  }

  @Test
  fun `badGatewayText pairs text with 502`() {
    val r = badGatewayText("upstream down")
    assert(r.text == "upstream down")
    assert(r.status == HttpStatusCode.BadGateway)
  }

  @Test
  fun `internalErrorText pairs text with 500`() {
    val r = internalErrorText("boom")
    assert(r.text == "boom")
    assert(r.status == HttpStatusCode.InternalServerError)
  }
}
