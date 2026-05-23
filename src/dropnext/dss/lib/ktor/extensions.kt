package dropnext.dss.lib.ktor

import dropnext.dss.lib.auth.constantTimeEquals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respondText


fun ApplicationRequest.shopifyAccessTokenFromHeader() =
  this.headers["X-Shopify-Access-Token"]?.trim()?.takeIf { it.isNotEmpty() }

/** 500 plain-text response — used by the `StatusPages` exception handler so secrets never leak. */
suspend fun ApplicationCall.respondErrorText(text: String) =
  respondText(text, status = HttpStatusCode.InternalServerError)

/** When [secret] is non-blank, require matching `X-DSS-Internal-Secret` header (constant-time compare). */
suspend fun ApplicationCall.requireDssInternalSecret(secret: String?): Boolean {
  if (secret.isNullOrBlank()) return true
  val provided = request.headers["X-DSS-Internal-Secret"] ?: ""
  if (!constantTimeEquals(secret, provided)) {
    respondError(DssError.Unauthorized())
    return false
  }
  return true
}
