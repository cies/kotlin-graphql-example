package dropnext.dss.shopify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val webhookJson = Json { ignoreUnknownKeys = true }

/**
 * Resolves an Admin GraphQL ID from a Shopify HTTP webhook JSON body.
 * Uses `admin_graphql_api_id` when present; otherwise builds `gid://shopify/{Product|Order}/{id}` from `id`.
 */
fun graphqlResourceIdFromShopifyWebhook(topic: String, bodyUtf8: String): String? {
  val root =
    runCatching { webhookJson.parseToJsonElement(bodyUtf8).jsonObject }.getOrNull() ?: return null
  root["admin_graphql_api_id"]?.jsonPrimitive?.contentOrNull?.let { return it }
  val idPrimitive = root["id"]?.jsonPrimitive ?: return null
  val numericId = idPrimitive.longOrNull ?: idPrimitive.content.toLongOrNull() ?: return null
  val resource =
    when {
      topic.startsWith("products/") -> "Product"
      topic.startsWith("orders/") -> "Order"
      else -> return null
    }
  return "gid://shopify/$resource/$numericId"
}

/**
 * Parses the legacy (integer) variant IDs from a Shopify `products/delete` webhook body.
 * Shopify includes the full product JSON (including variants) in delete payloads.
 */
/** Reads `domain` from a Shopify webhook JSON body when [X-Shopify-Shop-Domain] is absent. */
fun shopDomainFromWebhookBody(bodyUtf8: String): String? {
  val root =
    runCatching { webhookJson.parseToJsonElement(bodyUtf8).jsonObject }.getOrNull() ?: return null
  return root["domain"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

fun variantLegacyIdsFromProductWebhook(bodyUtf8: String): List<Long> {
  val root =
    runCatching { webhookJson.parseToJsonElement(bodyUtf8).jsonObject }.getOrNull() ?: return emptyList()
  val variants = root["variants"]?.jsonArray ?: return emptyList()
  return variants.mapNotNull { el ->
    runCatching { el.jsonObject["id"]?.jsonPrimitive?.longOrNull }.getOrNull()
  }
}
