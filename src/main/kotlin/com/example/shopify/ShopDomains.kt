package com.example.shopify

private val shopRegex =
  Regex("^(?:https?://)?([a-zA-Z0-9][a-zA-Z0-9\\-]*)\\.myshopify\\.com/?.*$")

/** Normalizes install/callback `shop` to `name.myshopify.com`. */
fun normalizeShopDomain(raw: String): String? {
  val trimmed = raw.trim().lowercase()
  if (trimmed.endsWith(".myshopify.com")) {
    val host = trimmed.removePrefix("https://").removePrefix("http://").substringBefore('/')
    return host
  }
  val match = shopRegex.matchEntire(trimmed) ?: return null
  return "${match.groupValues[1]}.myshopify.com"
}

fun adminGraphqlJsonUrl(shop: String, apiVersion: String): String =
  "https://$shop/admin/api/$apiVersion/graphql.json"
