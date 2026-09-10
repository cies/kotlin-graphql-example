package dropnext.dss.lib.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.MissingRequestParameterException
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
 * is answered in plain text; every other path gets the JSON [dropnext.dss.contract.ErrorResponse].
 */
fun Application.installStatusPages(plainTextErrorPaths: Set<String>) {
  install(StatusPages) {
    // Registered next to its parent `BadRequestException`: the plugin picks the nearest class.
    exception<MissingRequestParameterException> { call, cause ->
      val error = DssError.MissingParameter(cause.parameterName)
      if (call.request.path() in plainTextErrorPaths) call.respondTextError(error) else call.respondError(error)
    }
    exception<BadRequestException> { call, cause ->
      call.respondError(DssError.InvalidRequest("invalid request body: ${cause.message ?: "malformed JSON"}"))
    }
    exception<RequestValidationException> { call, cause ->
      call.respondError(DssError.InvalidRequest(cause.reasons.joinToString("; ")))
    }
    exception<Throwable> { call, cause ->
      // The path, never the URI: the OAuth callback's query string carries `code` and `hmac`.
      log.error(cause) { "Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}" }
      call.respondError(DssError.Internal)
    }
  }
}
