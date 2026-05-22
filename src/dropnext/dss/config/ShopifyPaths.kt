package dropnext.dss.config


/**
 * Outbound paths the DSS calls on Shopify (per-shop host, not the GraphQL proxy).
 * Each helper builds a fully-qualified `https://{shop}/…` URL.
 */
object ShopifyPaths {
  const val ADMIN_OAUTH_AUTHORIZE = "/admin/oauth/authorize"
  const val ADMIN_OAUTH_ACCESS_TOKEN = "/admin/oauth/access_token"

  /** Per-shop Admin GraphQL endpoint, parameterized by API version (e.g. `2026-04`). */
  fun adminApiGraphqlJson(apiVersion: String): String = "/admin/api/$apiVersion/graphql.json"
}
