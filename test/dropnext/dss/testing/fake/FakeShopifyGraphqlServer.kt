package dropnext.dss.testing.fake

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Embedded CIO server that fakes a Shopify Admin Graphql endpoint.
 * Records every POST'd request by `operationName` and serves a stubbed response payload.
 */
class FakeShopifyGraphqlServer {

  data class RecordedCall(
    val operationName: String,
    val variables: JsonElement,
    val authorization: String?,
    val rawBody: String,
  )

  val calls: MutableList<RecordedCall> = mutableListOf()

  private val responses: MutableMap<String, String> = mutableMapOf()
  private val responseQueues: MutableMap<String, ArrayDeque<String>> = mutableMapOf()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /** OAuth token-exchange response served on `POST /admin/oauth/access_token`. */
  @Volatile
  var oauthAccessTokenResponse: String =
    """{"access_token":"shpat_fake_admin_token","scope":"read_orders"}"""

  /** When non-null, the OAuth endpoint returns this status with [oauthAccessTokenResponse] as the body. */
  @Volatile
  var oauthStatus: HttpStatusCode = HttpStatusCode.OK

  /** Recorded OAuth token-exchange POSTs (raw JSON bodies). */
  val oauthCalls: MutableList<String> = mutableListOf()

  private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = 0) {
      routing {
        post("/admin/api/{version}/graphql.json") {
          val raw = call.receiveText()
          val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
          val op = parsed?.get("operationName")?.jsonPrimitive?.contentOrNull.orEmpty()
          val vars = parsed?.get("variables") ?: JsonNull
          val token = call.request.headers["X-Shopify-Access-Token"]
          calls.add(RecordedCall(op, vars, token, raw))
          val body =
            dequeueResponse(op)
              ?: responses[op]
              ?: """{"data":null,"errors":[{"message":"no stub for $op"}]}"""
          call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/admin/oauth/access_token") {
          oauthCalls.add(call.receiveText())
          call.respondText(oauthAccessTokenResponse, ContentType.Application.Json, oauthStatus)
        }
      }
    }

  fun start(): Int {
    server.start(wait = false)
    return runBlocking { server.engine.resolvedConnectors().first().port }
  }

  fun stop() {
    server.stop(gracePeriodMillis = 50, timeoutMillis = 500)
  }

  fun reset() {
    calls.clear()
    responses.clear()
    responseQueues.clear()
    oauthCalls.clear()
    oauthAccessTokenResponse = """{"access_token":"shpat_fake_admin_token","scope":"read_orders"}"""
    oauthStatus = HttpStatusCode.OK
  }

  /** Stub a raw response body for [operationName]. */
  fun stubRaw(operationName: String, responseJson: String) {
    responses[operationName] = responseJson
  }

  /** Stub a sequence of raw responses for [operationName]; each call dequeues the next entry. */
  fun stubSequence(operationName: String, vararg responseJson: String) {
    responseQueues[operationName] = ArrayDeque(responseJson.toList())
  }

  /** Enqueue a response served before any static [stubRaw] / [stubData] stub for [operationName]. */
  fun enqueueResponse(operationName: String, responseJson: String) {
    responseQueues.getOrPut(operationName) { ArrayDeque() }.addLast(responseJson)
  }

  private fun dequeueResponse(operationName: String): String? =
    responseQueues[operationName]?.removeFirstOrNull()

  /** Stub `{"data": <serialized payload>}` for [operationName] from a typed payload. */
  fun <T : Any> stubData(operationName: String, payload: T, serializer: KSerializer<T>) {
    val dataJson = json.encodeToJsonElement(serializer, payload)
    val response = buildJsonObject {
      put("data", dataJson)
    }
    responses[operationName] = response.toString()
  }

  fun shopUrl(version: String = "2026-04"): String =
    "http://localhost:${runBlocking { server.engine.resolvedConnectors().first().port }}/admin/api/$version/graphql.json"
}
