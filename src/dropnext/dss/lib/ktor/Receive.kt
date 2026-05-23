package dropnext.dss.lib.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import kotlinx.serialization.SerializationException


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
suspend inline fun <reified A : Any> ApplicationCall.receiveOr400(): A? =
  try {
    receive<A>()
  } catch (e: BadRequestException) {
    respondError(DssError.InvalidRequest("invalid request body: ${e.message ?: "malformed JSON"}"))
    null
  } catch (e: SerializationException) {
    respondError(DssError.InvalidRequest("invalid request body: ${e.message ?: "missing or wrong-typed fields"}"))
    null
  }
