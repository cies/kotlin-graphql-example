package dropnext.dss.lib.ktor.plugin

import dropnext.dss.lib.dto.ErrorResponse
import dropnext.dss.lib.ktor.constantTimeEquals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route


// TODO: make this use HTTP header "Bearer: <token>" -- more clear more common
// rename file to requireDssAuthHeader (and rename relevant symbols in this file alike)

const val DSS_INTERNAL_SECRET_AUTH = "dss-internal-secret"

/**
 * Installs an [Authentication] provider named [DSS_INTERNAL_SECRET_AUTH] that requires every
 * request inside a [requireDssInternalSecret] scope to carry a matching `X-DSS-Internal-Secret`
 * header (constant-time compared against [secret]).
 *
 * When [secret] is `null` or blank, the provider accepts every call — handy for local dev without a configured secret.
 * // TODO: should this be how it works? why the dangerous escape hatch?
 *
 * Handlers never have to call an auth helper themselves:
 * a missing/invalid header short-circuits with `401 unauthorized` JSON before the route handler runs.
 */
fun Application.installDssInternalSecretAuth(secret: String?) {
  install(Authentication) {
    dssInternalSecret(secret)
  }
}

/** Wraps [build] in `authenticate(DSS_INTERNAL_SECRET_AUTH)` — used by [dropnext.dss.routing.installDssRoutes]. */
fun Route.requireDssInternalSecret(build: Route.() -> Unit) {
  authenticate(DSS_INTERNAL_SECRET_AUTH) { build() }
}

private fun AuthenticationConfig.dssInternalSecret(secret: String?) {
  register(DssInternalSecretProvider(secret))
}

private class DssInternalSecretProvider(
  private val secret: String?,
) : AuthenticationProvider(Config(DSS_INTERNAL_SECRET_AUTH)) {

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    if (secret.isNullOrBlank()) {
      context.principal(DSS_INTERNAL_SECRET_AUTH, UserIdPrincipal("dss-no-secret-configured"))
      return
    }
    val provided = context.call.request.headers["X-DSS-Internal-Secret"].orEmpty()
    if (constantTimeEquals(secret, provided)) {
      context.principal(DSS_INTERNAL_SECRET_AUTH, UserIdPrincipal("dss-internal"))
    } else {
      context.challenge(DSS_INTERNAL_SECRET_AUTH, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
        challenge.complete()
      }
    }
  }

  private class Config(name: String) : AuthenticationProvider.Config(name)
}
