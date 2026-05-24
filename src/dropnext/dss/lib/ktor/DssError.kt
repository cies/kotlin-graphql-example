package dropnext.dss.lib.ktor

import dropnext.dss.lib.monolith.dto.generated.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText


/**
 * Outbound error taxonomy for DSS handlers. One sealed hierarchy means handlers describe the
 * failure shape and the central [respondError] / [respondTextError] helpers decide the HTTP
 * status and body — no scattered `respond(BadRequest, …)` calls.
 *
 * JSON endpoints (the OpenAPI DSS contract — DssHttpHandlers) use [respondError], which emits an
 * [ErrorResponse]. The HTML OAuth flow uses [respondTextError] so error pages stay plain text.
 */
sealed interface DssError {
  val message: String

  /** 400 — generic client-side validation / payload problem. */
  data class InvalidRequest(override val message: String) : DssError

  /** 400 — required query parameter is missing. */
  data class MissingParameter(val name: String) : DssError {
    override val message: String = "Missing $name"
  }

  /** 400 — supplied query parameter is structurally invalid. */
  data class InvalidParameter(val name: String, val detail: String? = null) : DssError {
    override val message: String = if (detail == null) "Invalid $name" else "Invalid $name: $detail"
  }

  /** 401 — the internal-secret guard rejected the request. */
  data class Unauthorized(override val message: String = "unauthorized") : DssError

  /** 401 — no Shopify Admin token resolvable for the shop in question. */
  data object MissingShopifyAdminToken : DssError {
    override val message: String =
      "missing Shopify Admin token: configure DSS_SHOP_ACCESS_TOKENS, complete OAuth install, " +
        "or persist a token via PUT /stores/api-key"
  }

  /** 403 — HMAC or signed-state verification failed. */
  data class InvalidSignature(override val message: String) : DssError

  /** 404 — a referenced resource (order, fulfillment, …) does not exist. */
  data class NotFound(override val message: String) : DssError

  /** 502 — upstream call (Shopify Admin, monolith) failed and the request cannot continue. */
  data class UpstreamFailure(override val message: String) : DssError
}

fun DssError.toHttpStatus(): HttpStatusCode = when (this) {
  is DssError.InvalidRequest,
  is DssError.MissingParameter,
  is DssError.InvalidParameter,
    -> HttpStatusCode.BadRequest

  is DssError.Unauthorized,
  is DssError.MissingShopifyAdminToken,
    -> HttpStatusCode.Unauthorized

  is DssError.InvalidSignature -> HttpStatusCode.Forbidden

  is DssError.NotFound -> HttpStatusCode.NotFound

  is DssError.UpstreamFailure -> HttpStatusCode.BadGateway
}

/** JSON-error responder for the OpenAPI DSS contract. Body is [ErrorResponse] with `error: <message>`. */
suspend fun ApplicationCall.respondError(e: DssError) {
  respond(e.toHttpStatus(), ErrorResponse(error = e.message))
}

/** Plain-text error responder, used by OAuth/install routes that render HTML on success. */
suspend fun ApplicationCall.respondTextError(e: DssError) {
  respondText(e.message, status = e.toHttpStatus())
}
