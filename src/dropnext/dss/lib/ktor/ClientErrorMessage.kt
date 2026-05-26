package dropnext.dss.lib.ktor


/**
 * For HTTP 4xx JSON when Graphql/Shopify [Throwable]s escape (e.g.: invalid access token, TLS).
 * Full stack traces stay in server logs; do not return secrets in env-driven messages.
 */
// TODO: not Exception instead of Throwable? Or remove this code?
fun clientErrorMessage(throwable: Throwable): String {
  val m = throwable.message
  if (!m.isNullOrBlank()) return m.replace("\n", " ").take(1_200)
  return throwable::class.simpleName ?: "Throwable"
}
