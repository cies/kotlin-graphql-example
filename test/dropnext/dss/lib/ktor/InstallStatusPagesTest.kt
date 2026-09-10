package dropnext.dss.lib.ktor

import dropnext.dss.contract.ErrorResponse
import dropnext.dss.lib.json.AppJson
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * What the one exception-to-answer mapping guarantees: Ktor's own request-shape exceptions stay 400s
 * instead of falling into the catch-all, a missing query parameter names itself (in plain text on the
 * paths a browser reads), and the catch-all logs the path without the query string, where the OAuth
 * callback's `code` and `hmac` travel.
 */
class InstallStatusPagesTest {

  private fun throwingApp(
    cause: Throwable,
    plainTextErrorPaths: Set<String> = emptySet(),
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      installStatusPages(plainTextErrorPaths)
      installJsonContentNegotiation()
      routing { get("/boom") { throw cause } }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.errorResponse(path: String): Pair<HttpStatusCode, ErrorResponse> {
    val client = createClient { install(ClientContentNegotiation) { json(AppJson) } }
    val response = client.get(path)
    return response.status to response.body<ErrorResponse>()
  }

  @Test
  fun `a body Ktor could not decode is a 400, not a 500`() = throwingApp(BadRequestException("Failed to convert request body")) {
    val (status, error) = errorResponse("/boom")
    assert(status == HttpStatusCode.BadRequest)
    assert(error.error == "invalid request body: Failed to convert request body")
  }

  @Test
  fun `a failed request validation is a 400 listing every reason`() =
    throwingApp(RequestValidationException(Any(), listOf("first reason", "second reason"))) {
      val (status, error) = errorResponse("/boom")
      assert(status == HttpStatusCode.BadRequest)
      assert(error.error == "first reason; second reason")
    }

  @Test
  fun `a missing query parameter is a 400 naming the parameter`() =
    throwingApp(MissingRequestParameterException("shop")) {
      val (status, error) = errorResponse("/boom")
      assert(status == HttpStatusCode.BadRequest)
      assert(error.error == "Missing shop")
    }

  @Test
  fun `a missing query parameter on a plain-text path is answered in plain text`() =
    throwingApp(MissingRequestParameterException("shop"), plainTextErrorPaths = setOf("/boom")) {
      val response = client.get("/boom")
      assert(response.status == HttpStatusCode.BadRequest)
      assert(response.contentType()?.withoutParameters() == ContentType.Text.Plain)
      assert(response.bodyAsText() == "Missing shop")
    }

  @Test
  fun `anything else is the generic 500`() = throwingApp(IllegalStateException("the database is on fire")) {
    val (status, error) = errorResponse("/boom")
    assert(status == HttpStatusCode.InternalServerError)
    assert(error.error == "internal error")
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the unhandled-error line names the method and the path but never the query string`() {
    val lines = capturingLogs {
      throwingApp(IllegalStateException("boom")) {
        client.get("/boom?code=super-secret-code&hmac=deadbeef&shop=acme.myshopify.com")
      }
    }

    val line = lines.single { "Unhandled error" in it }
    assert("GET /boom" in line)
    assert("super-secret-code" !in line)
    assert("deadbeef" !in line)
    assert("code=" !in line)
  }
}
