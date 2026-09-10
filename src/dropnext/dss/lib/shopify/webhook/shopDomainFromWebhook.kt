package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.json.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * Resolves the shop for an inbound webhook.
 * Shopify notoriously puts the domain either in the header or in the JSON body.
 *
 * Prefers the [shopDomainHeader] (`X-Shopify-Shop-Domain`) — Shopify's canonical signal per its
 * webhook delivery contract — and falls back to the optional JSON `domain` field when the header
 * is missing (older topic payloads, or a reverse proxy that strips the header in dev).
 */
fun shopDomainFromWebhook(shopDomainHeader: String?, webhookBody: String?): ShopDomain? {
  shopDomainHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { return ShopDomain.parse(it) }

  if (webhookBody == null) return null
  val root = runCatching { AppJson.parseToJsonElement(webhookBody).jsonObject }.getOrNull()
    ?: return null
  return root["domain"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { ShopDomain.parse(it) }
}
