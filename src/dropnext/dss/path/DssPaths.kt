package dropnext.dss.path


/**
 * Inbound HTTP paths this service exposes. Single source of truth for route definitions,
 * log strings, UI text, and the `/webhooks/shopify` callback URL we register with Shopify.
 *
 * Canonical contract for DSS-internal endpoints: repo root **`openapi.json`**.
 * Keep these in sync with the spec — see `MonolithPaths` for the outbound counterpart.
 */
object DssPaths {
  // --- Diagnostics ---
  const val INDEX = "/"
  const val HEALTH = "/health"
  const val API = "/api"
  const val API_CHECK = "/api/check"
  const val API_REDIRECT_URL = "/api/redirect-url"

  // --- Shopify OAuth ---
  const val INSTALL = "/install"

  /** Default OAuth callback path; the actual value comes from `ShopifyConfig.oauthRedirectPath` (env `OAUTH_REDIRECT_PATH`). */
  const val DEFAULT_OAUTH_CALLBACK = "/oauth/callback"

  // --- Shopify webhooks ---
  const val WEBHOOKS_SHOPIFY = "/webhooks/shopify"

  // --- DSS internal REST (called by monolith) ---
  const val STORES_API_KEY = "/stores/api-key"
  const val SYNC_SHIPMENTS_WITH_FULFILLMENTS = "/sync-shipments-with-fulfillments"
  const val TRACKING_UPDATES = "/tracking-updates"
  const val TRACKING_UPDATE = "/tracking-update"

  // --- Demo / smoke-test routes (only when enableDemoRoutes) ---
  const val DEMO_PRODUCTS = "/demo/products"
  const val DEMO_ORDER = "/demo/order"
  const val DEMO_FULFILLMENT_CREATE = "/demo/fulfillment/create"
  const val DEMO_FULFILLMENT_TRACKING = "/demo/fulfillment/tracking"
}
