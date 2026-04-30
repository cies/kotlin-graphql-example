package dropnext.dss.shopify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
