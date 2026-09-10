package dropnext.dss.lib.shopify.oauth

import dropnext.dss.domain.ShopDomain


/**
 * Outbound paths the DSS calls on Shopify (per-shop host, not the Graphql proxy).
 * Each helper builds a fully-qualified `https://{shop}/…` URL.
 */
@Suppress("ConstPropertyName") // Less shouty field names.
object OutBoundShopifyOAuthPaths {
  const val adminOAuthAuthorize =
    "/admin/oauth/authorize"

  const val adminOAuthAccessToken =
    "/admin/oauth/access_token"

  /** Per-shop Admin Graphql endpoint, parameterized by API version (e.g. `2026-04`). */
  fun adminApiGraphqlJson(apiVersion: String): String =
    "/admin/api/$apiVersion/graphql.json"
}

/** Per-shop Admin Graphql endpoint, e.g. `https://acme.myshopify.com/admin/api/2026-04/graphql.json`. */
fun ShopDomain.adminGraphqlUrl(apiVersion: String): String =
  "https://$normalizedShopifyHost${OutBoundShopifyOAuthPaths.adminApiGraphqlJson(apiVersion)}"
