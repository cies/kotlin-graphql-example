package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.lib.json.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
  val numericId = numericIdOf(root) ?: return null
  val resource = when {
    topic.startsWith("products/") -> "Product"
    topic.startsWith("orders/") -> "Order"
    else -> return null
  }
  return "gid://shopify/$resource/$numericId"
}

/**
 * The product id of a `products/delete` webhook. Its body is `{"id": …}` and nothing more: the
 * product is gone by the time it fires, so this id is all there is to tell the monolith.
 */
fun productIdFromProductWebhook(bodyUtf8: String): ShopifyProductId? =
  parseWebhookBody(bodyUtf8)?.let(::numericIdOf)?.let(::ShopifyProductId)

private fun parseWebhookBody(bodyUtf8: String): JsonObject? =
  runCatching { AppJson.parseToJsonElement(bodyUtf8).jsonObject }.getOrNull()

/** Shopify writes `id` as a JSON number, but a quoted one is read too: a `Long` is a `Long`. */
private fun numericIdOf(root: JsonObject): Long? {
  val idPrimitive = root["id"]?.jsonPrimitive ?: return null
  return idPrimitive.longOrNull ?: idPrimitive.content.toLongOrNull()
}
