package dropnext.dss.lib.ktor.plugin

import dropnext.dss.lib.monolith.dto.generated.ErrorResponse
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



const val MONOLITH_WEBHOOK_AUTH_SECRET_KEY = "monolith-webhook-auth-secret"


/** Wraps [build] in `authenticate(MONOLITH_WEBHOOK_AUTH_SECRET_KEY)` — used by [dropnext.dss.routing.installMonolithWebhookRoutes]. */
fun Route.requireMonolithWebhookAuthHeader(build: Route.() -> Unit) {
  authenticate(MONOLITH_WEBHOOK_AUTH_SECRET_KEY) { build() }
}

/**
 * Installs an [Authentication] provider named [MONOLITH_WEBHOOK_AUTH_SECRET_KEY] that requires every
 * request inside a [requireMonolithWebhookAuthHeader] scope to carry a matching `X-DSS-Internal-Secret`
 * header (constant-time compared against [secret]).
 *
 * When [secret] is `null` or blank, the provider accepts every call — handy for local dev without a configured secret.
 * // TODO: should this be how it works? why the dangerous escape hatch?
 *
 * Handlers never have to call an auth helper themselves:
 * a missing/invalid header short-circuits with `401 unauthorized` JSON before the route handler runs.
 */
fun Application.installMonolithWebhookAuth(secret: String?) {
  install(Authentication) {
    monolithWebhookAuthSecret(secret)
  }
}

private fun AuthenticationConfig.monolithWebhookAuthSecret(secret: String?) {
  register(MonolithWebhookAuthSecretProvider(secret))
}

private class MonolithWebhookAuthSecretProvider(
  private val secret: String?,
) : AuthenticationProvider(Config(MONOLITH_WEBHOOK_AUTH_SECRET_KEY)) {

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    if (secret.isNullOrBlank()) {
      context.principal(MONOLITH_WEBHOOK_AUTH_SECRET_KEY, UserIdPrincipal("dss-no-secret-configured"))
      return
    }
    // TODO: replace with Bearer...

    // TODO(or better): replace shared-secret comparison with Stripe-style HMAC verification:
    // monolith signs (timestamp + body) with the shared key; DSS verifies the signature
    // and rejects timestamps outside a small skew window. Avoids leaking the secret
    // over the wire even once, and gives us replay protection for free.
    val provided = context.call.request.headers["X-DSS-Internal-Secret"].orEmpty()
    if (constantTimeEquals(secret, provided)) {
      context.principal(MONOLITH_WEBHOOK_AUTH_SECRET_KEY, UserIdPrincipal("dss-internal"))
    } else {
      context.challenge(MONOLITH_WEBHOOK_AUTH_SECRET_KEY, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
        challenge.complete()
      }
    }
  }

  private class Config(name: String) : AuthenticationProvider.Config(name)
}
