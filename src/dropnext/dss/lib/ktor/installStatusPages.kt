package dropnext.dss.lib.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path


private val log = KotlinLogging.logger {}

/**
 * The one place an exception becomes an answer. Ktor signals a body it cannot decode, a request that
 * fails validation or a query parameter `getOrFail` did not find by throwing, and would answer the
 * first with a 400 on its own; the catch-all that turns a bug into the generic 500 of
 * [DssError.Internal] would swallow that, hence the explicit mappings in front of it.
 *
 * A request to one of [plainTextErrorPaths] (the OAuth routes, which a merchant's browser reads)
 * is answered in plain text, whatever the exception; every other path gets the JSON
 * [dropnext.dss.contract.ApiError]. Every mapping goes through the one responder that knows the
 * difference, so a new mapping cannot forget it.
 */
fun Application.installStatusPages(plainTextErrorPaths: Set<String>) {
  suspend fun ApplicationCall.respondShaped(error: DssError) =
    if (request.path() in plainTextErrorPaths) respondTextError(error) else respondError(error)

  install(StatusPages) {
    // Registered next to its parent `BadRequestException`: the plugin picks the nearest class.
    exception<MissingRequestParameterException> { call, cause ->
      call.respondShaped(DssError.MissingParameter(cause.parameterName))
    }
    exception<BadRequestException> { call, cause ->
      call.respondShaped(DssError.InvalidRequest("invalid request body: ${cause.message ?: "malformed JSON"}"))
    }
    exception<RequestValidationException> { call, cause ->
      call.respondShaped(DssError.InvalidRequest(cause.reasons.joinToString("; ")))
    }
    // Thrown by `RequestBodyLimit` mid-read; without this mapping the catch-all below would call it a bug.
    exception<PayloadTooLargeException> { call, _ ->
      call.respondShaped(DssError.PayloadTooLarge)
    }
    exception<Throwable> { call, cause ->
      // The path, never the URI: the OAuth callback's query string carries `code` and `hmac`.
      log.error(cause) { "Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}" }
      call.respondShaped(DssError.Internal)
    }
  }
}
