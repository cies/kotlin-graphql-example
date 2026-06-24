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
 * request inside a [requireMonolithWebhookAuthHeader] scope to carry
 * `Authorization: Bearer <secret>` (constant-time compared against [secret]).
 *
 * Handlers never have to call an auth helper themselves:
 * a missing/invalid header short-circuits with `401 unauthorized` JSON before the route handler runs.
 */
fun Application.installMonolithWebhookAuth(secret: String) {
  install(Authentication) {
    monolithWebhookAuthSecret(secret)
  }
}

private fun AuthenticationConfig.monolithWebhookAuthSecret(secret: String) {
  register(MonolithWebhookAuthSecretProvider(secret))
}

private class MonolithWebhookAuthSecretProvider(
  private val secret: String,
) : AuthenticationProvider(Config(MONOLITH_WEBHOOK_AUTH_SECRET_KEY)) {

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    val authHeader = context.call.request.headers["Authorization"].orEmpty()
    val bearerPrefix = "Bearer "
    val provided = if (authHeader.startsWith(bearerPrefix)) {
      authHeader.removePrefix(bearerPrefix)
    } else {
      ""
    }
    if (constantTimeEquals(secret, provided)) {
      context.principal(MONOLITH_WEBHOOK_AUTH_SECRET_KEY, UserIdPrincipal("dss-internal"))
    } else {
      context.challenge(MONOLITH_WEBHOOK_AUTH_SECRET_KEY, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "unauthorized"))
        challenge.complete()
      }
    }
  }

  // Follow-up hardening option: use Stripe-style HMAC signatures with timestamp + body
  // to add replay protection and avoid sending the shared secret in plaintext.
  private class Config(name: String) : AuthenticationProvider.Config(name)
}
