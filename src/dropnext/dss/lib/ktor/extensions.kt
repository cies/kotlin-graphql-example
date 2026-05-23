package dropnext.dss.lib.ktor

import dropnext.dss.lib.auth.constantTimeEquals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException


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

/**
 * Decodes the request body as [A], or responds 400 with a JSON [dropnext.dss.lib.dto.ErrorResponse]
 * and returns `null`. Catches the two failure modes kotlinx.serialization surfaces through ktor:
 * [BadRequestException] (top-level parse failure) and [SerializationException] (field-level —
 * missing required, wrong type). Without this helper, both would escape to `StatusPages` and
 * become a misleading 500.
 *
 * Use:
 * ```kotlin
 * val body = call.receiveOr400<SyncShipmentsWithFulfillmentsRequest>() ?: return
 * ```
 */
suspend inline fun <reified A : Any> ApplicationCall.receiveOr400(): A? = try {
  receive<A>()
} catch (e: BadRequestException) {
  respondError(DssError.InvalidRequest("invalid request body: ${e.message ?: "malformed JSON"}"))
  null
} catch (e: SerializationException) {
  respondError(DssError.InvalidRequest("invalid request body: ${e.message ?: "missing or wrong-typed fields"}"))
  null
}
