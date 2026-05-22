package dropnext.dss.lib.ktor

import dropnext.dss.lib.dss.constantTimeEquals
import dropnext.dss.lib.dss.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respond
import io.ktor.server.response.respondText


fun ApplicationRequest.shopifyAccessTokenFromHeader() =
  this.headers["X-Shopify-Access-Token"]?.trim()?.takeIf { it.isNotEmpty() }

/** Plain-text response pair (body + HTTP status), constructed pure-side for unit-testability. */
internal data class TextStatus(val text: String, val status: HttpStatusCode)

internal fun badRequestText(text: String) = TextStatus(text, HttpStatusCode.BadRequest)
internal fun forbiddenText(text: String) = TextStatus(text, HttpStatusCode.Forbidden)
internal fun badGatewayText(text: String) = TextStatus(text, HttpStatusCode.BadGateway)
internal fun internalErrorText(text: String) = TextStatus(text, HttpStatusCode.InternalServerError)

suspend fun ApplicationCall.respondBadRequestText(text: String) =
  badRequestText(text).let { respondText(it.text, status = it.status) }

suspend fun ApplicationCall.respondForbiddenText(text: String) =
  forbiddenText(text).let { respondText(it.text, status = it.status) }

suspend fun ApplicationCall.respondBadGatewayText(text: String) =
  badGatewayText(text).let { respondText(it.text, status = it.status) }

suspend fun ApplicationCall.respondErrorText(text: String) =
  internalErrorText(text).let { respondText(it.text, status = it.status) }

/** When [secret] is non-blank, require matching `X-DSS-Internal-Secret` header (constant-time compare). */
suspend fun ApplicationCall.requireDssInternalSecret(secret: String?): Boolean {
  if (secret.isNullOrBlank()) return true
  val provided = request.headers["X-DSS-Internal-Secret"] ?: ""
  if (!constantTimeEquals(secret, provided)) {
    respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
    return false
  }
  return true
}
