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

suspend fun ApplicationCall.respondBadRequestText(text: String) =
  this.respondText(text, status = HttpStatusCode.BadRequest)

suspend fun ApplicationCall.respondForbiddenText(text: String) =
  this.respondText(text, status = HttpStatusCode.Forbidden)

suspend fun ApplicationCall.respondBadGatewayText(text: String) =
  this.respondText(text, status = HttpStatusCode.BadGateway)

suspend fun ApplicationCall.respondErrorText(text: String) =
  this.respondText(text, status = HttpStatusCode.InternalServerError)

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
