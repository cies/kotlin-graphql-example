package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.lib.json.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull


// Helpers for pulling small bits of information out of Shopify HTTP webhook bodies BEFORE we make a
// follow-up Graphql call to load the full resource. Verifying the HMAC stays in `ShopifyHmacVerifierService`;
// these parsers assume the body is already trusted.

/**
 * Resolves an Admin Graphql ID from a Shopify HTTP webhook JSON body.
 * Uses `admin_graphql_api_id` when present; otherwise builds `gid://shopify/{Product|Order}/{id}` from `id`.
 */
fun graphqlResourceIdFromShopifyWebhook(topic: String, bodyUtf8: String): String? {
  val root = parseWebhookBody(bodyUtf8) ?: return null
  root["admin_graphql_api_id"]?.jsonPrimitive?.contentOrNull?.let { return it }
  val idPrimitive = root["id"]?.jsonPrimitive ?: return null
  val numericId = idPrimitive.longOrNull ?: idPrimitive.content.toLongOrNull() ?: return null
  val resource = when {
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
fun variantIdsFromProductWebhook(bodyUtf8: String): List<ShopifyVariantId> {
  val root = parseWebhookBody(bodyUtf8) ?: return emptyList()
  val variants = root["variants"]?.jsonArray ?: return emptyList()
  return variants.mapNotNull { el ->
    runCatching { el.jsonObject["id"]?.jsonPrimitive?.longOrNull }.getOrNull()?.let(::ShopifyVariantId)
  }
}

private fun parseWebhookBody(bodyUtf8: String): JsonObject? =
  runCatching { AppJson.parseToJsonElement(bodyUtf8).jsonObject }.getOrNull()
