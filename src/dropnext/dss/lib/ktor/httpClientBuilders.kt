package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher


/**
 * How long one monolith call may take before it is given up on. A healthy monolith answers every
 * endpoint the DSS calls in well under a second: each is one transaction on indexed rows (a store
 * lookup, an order insert plus a queue message, an upsert of at most one product's variants), so ten
 * seconds is an order of magnitude of headroom for a cold connection pool or a GC pause. Waiting
 * longer buys nothing: Shopify abandons a webhook delivery after five seconds and redelivers it, so
 * a call that is still pending at ten seconds already belongs to a request nobody is waiting for,
 * and the monolith's own idempotency (a `409` for a repeated order, upserts and soft-deletes) is
 * what makes that redelivery safe. It also keeps the worst case bounded: the retries below add up
 * to under a minute, where the shared client's two minutes per attempt once allowed eight.
 */
const val MONOLITH_REQUEST_TIMEOUT_MILLIS = 10_000L

/** Connection resets and refused connections; a timeout of any kind is not one of these. */
const val MONOLITH_MAX_RETRIES = 3

/**
 * OkHttp's dispatcher lets five requests per host through at a time, a browser-like default. Every
 * call this service makes goes to one of two hosts, the monolith and the shop's Shopify domain, and a
 * webhook burst is exactly that many concurrent calls to the same host: the sixth would wait in the
 * dispatcher's queue, holding its webhook open at Shopify meanwhile. Sized for a burst, not for the CPU.
 */
const val MAX_CONCURRENT_REQUESTS_PER_HOST = 32
const val MAX_CONCURRENT_REQUESTS = 64

fun createSharedHttpClient(): HttpClient = HttpClient(OkHttp) {
  engine {
    config {
      dispatcher(
        Dispatcher().apply {
          maxRequests = MAX_CONCURRENT_REQUESTS
          maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
        },
      )
      connectTimeout(15, TimeUnit.SECONDS)
      readTimeout(120, TimeUnit.SECONDS)
      writeTimeout(120, TimeUnit.SECONDS)
    }
  }
  install(HttpTimeout) {
    requestTimeoutMillis = 120_000
    connectTimeoutMillis = 15_000
    socketTimeoutMillis = 120_000
  }
  install(ContentNegotiation) {
    json(AppJson)
  }
  // No Logging plugin: avoids accidentally logging Authorization headers or JSON bodies to monolith/Shopify.
}

/**
 * Derives the monolith-specific [HttpClient]: a short request timeout ([MONOLITH_REQUEST_TIMEOUT_MILLIS],
 * overridable for tests), retries with exponential backoff on a dropped or refused connection and on a
 * `5xx`, and none on a timeout. Every monolith endpoint the DSS calls is idempotent, so repeating a
 * call the monolith may already have processed is safe; a timed-out call is not repeated because the
 * time budget, not the outcome, is what ran out, and three more attempts would only hold the webhook
 * longer. Shopify Graphql and OAuth traffic keep the base [createSharedHttpClient]: its mutations
 * carry no idempotency key, so a retried `fulfillmentCreate` would ship the same parcel twice.
 *
 * It also forwards the request's trace id, read from the coroutine context the server's `CallId` plugin
 * fills, so the monolith's log lines for a call can be found from ours. Shopify does not get the header.
 * The OkHttp engine and connection pool are shared.
 */
fun createMonolithHttpClient(
  baseHttpClient: HttpClient,
  requestTimeoutMillis: Long = MONOLITH_REQUEST_TIMEOUT_MILLIS,
): HttpClient = baseHttpClient.config {
  install(HttpTimeout) {
    this.requestTimeoutMillis = requestTimeoutMillis
    socketTimeoutMillis = requestTimeoutMillis
  }
  install(HttpRequestRetry) {
    // Spelled out in full: the plugin's defaults retry server errors and exceptions, and a call to
    // `retryOnExceptionIf` alone replaces only the exception half, leaving the other in place unseen.
    maxRetries = MONOLITH_MAX_RETRIES
    retryOnServerErrors(MONOLITH_MAX_RETRIES)
    retryOnExceptionIf(MONOLITH_MAX_RETRIES) { _, cause -> cause is IOException && !cause.isTimeout() }
    exponentialDelay(base = 2.0, maxDelayMs = 4_000)
  }
  install(CallId) {
    addToHeader(TRACE_ID_HEADER)
  }
}

/** Ktor's own three timeout exceptions; `HttpRequestTimeoutException` is an `IOException` too, hence the explicit check. */
private fun Throwable.isTimeout(): Boolean =
  this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException
