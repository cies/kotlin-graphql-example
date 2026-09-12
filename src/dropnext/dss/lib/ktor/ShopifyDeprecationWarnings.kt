package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


private val log = KotlinLogging.logger {}

/** Where Shopify lists the deprecated things a request used, such as `Shop.products, Shop.product`. */
const val SHOPIFY_DEPRECATION_HEADER = "X-Shopify-API-Deprecated-Reason"

/** A bound on what the plugin remembers, so a reason that varied per request could not grow the set without end. */
private const val MAX_REPORTED_DEPRECATIONS = 200

/**
 * Warns, once per operation and reason, when Shopify reports that a request used something deprecated. The codegen
 * refuses deprecated fields in an operation's selection, but it cannot see deprecated input fields, which
 * introspection leaves out of the committed schema, nor what Shopify deprecates after the schema was fetched. Shopify
 * names both in this header, and a deprecation is removed within a year, so it has to reach a human before the version
 * that removes it. Installed on the shared client; the monolith client derived from it never gets the header.
 */
val ShopifyDeprecationWarnings = createClientPlugin("ShopifyDeprecationWarnings") {
  val reported = ConcurrentHashMap.newKeySet<String>()
  onResponse { response ->
    val reason = response.headers[SHOPIFY_DEPRECATION_HEADER]?.takeIf { it.isNotBlank() } ?: return@onResponse
    val request = response.call.request
    val operation = graphqlOperationName(request.content) ?: request.url.encodedPath
    if (reported.size >= MAX_REPORTED_DEPRECATIONS || !reported.add("$operation $reason")) return@onResponse
    log.warn { "Shopify reports deprecated API use operation=$operation reason=\"$reason\"" }
  }
}

/** Every Graphql request has the same URL, so the operation is read from the body, which graphql-kotlin sends as JSON text. */
private fun graphqlOperationName(content: OutgoingContent): String? {
  val body = (content as? TextContent)?.text ?: return null
  return runCatching { AppJson.parseToJsonElement(body).jsonObject["operationName"]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
