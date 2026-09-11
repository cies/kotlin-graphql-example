package dropnext.dss.lib.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit


/**
 * The largest request body any route accepts. The webhook route reads the whole body before it can
 * verify the HMAC, and Ktor's CIO engine has no cap of its own, so without this anyone could make the
 * service allocate a body of any size. Shopify's largest payloads are the product webhooks, which
 * carry the full product with every variant and image: a few hundred kilobytes for a large product,
 * low single-digit megabytes for one at the variant limit. The monolith's bodies are far smaller.
 * (Kotlin nests comments, so the topic glob is not spelled out here.)
 */
const val MAX_REQUEST_BODY_BYTES: Long = 8L * 1024 * 1024

/** A body over [MAX_REQUEST_BODY_BYTES] aborts the read; `installStatusPages` answers the `413`. */
fun Application.installRequestBodyLimit() {
  install(RequestBodyLimit) {
    bodyLimit { MAX_REQUEST_BODY_BYTES }
  }
}
