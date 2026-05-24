package dropnext.dss.path


/**
 * Inbound HTTP paths this service exposes. Single source of truth for route definitions,
 * log strings, UI text, and the `/webhooks/shopify` callback URL we register with Shopify.
 *
 * Canonical contract for DSS-internal endpoints: repo root **`openapi.json`**.
 * Keep these in sync with the spec — see [dropnext.dss.lib.monolith.OutBoundMonolithPaths] and [dropnext.dss.lib.shopify.oauth.OutBoundShopifyOAuthPaths]
 * for the outbound counterparts.
 */
@Suppress("ConstPropertyName") // Less shouty field names.
object Paths {
  // --- Diagnostics ---
  const val index = "/"
  const val health = "/health"
  const val api = "/api"
  const val apiCheck = "/api/check"
  const val apiRedirectUrl = "/api/redirect-url"

  // --- Shopify OAuth ---
  const val install = "/install"

  /** Default OAuth callback path; the actual value comes from `ShopifyConfig.oauthRedirectPath` (env `OAUTH_REDIRECT_PATH`). */
  const val defaultOAuthCallback = "/oauth/callback"

  // --- Shopify webhooks ---
  const val webhooksShopify = "/webhooks/shopify"

  // --- DSS internal REST (called by monolith) ---
  const val storesApiKey = "/stores/api-key"
  const val syncShipmentsWithFulfillments = "/sync-shipments-with-fulfillments"
  const val trackingUpdates = "/tracking-updates"
  const val trackingUpdate = "/tracking-update"

  // --- Demo / smoke-test routes (only when enableDemoRoutes) ---
  const val demoProducts = "/demo/products"
  const val demoOrder = "/demo/order"
  const val demoFulfillmentCreate = "/demo/fulfillment/create"
  const val demoFulfillmentTracking = "/demo/fulfillment/tracking"
}
