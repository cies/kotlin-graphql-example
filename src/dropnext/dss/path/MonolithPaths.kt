package dropnext.dss.path


/**
 * Outbound paths the DSS calls on the DropNext monolith. Appended to
 * `MonolithConfig.baseUrl` (+ optional `MonolithConfig.apiPrefix`) by `HttpMonolithService`.
 *
 * The order-create path defaults to [ORDERS] but is overridable via env
 * `MONOLITH_CREATE_ORDER_PATH` (see [dropnext.dss.config.MonolithConfig.createOrderPath]).
 */
object MonolithPaths {
  /** `POST /orders` — create a Shopify order in the monolith. Default for [dropnext.dss.config.MonolithConfig.createOrderPath]. */
  const val ORDERS = "/orders"

  /** `GET /stores` — look up a store by `shopify_subdomain`. */
  const val STORES = "/stores"

  /** `PUT /stores/api-key` — persist the Shopify Admin token after OAuth install. */
  const val STORES_API_KEY = "/stores/api-key"

  /** `POST|GET|DELETE /product-variants` — upsert / list / soft-delete variants. */
  const val PRODUCT_VARIANTS = "/product-variants"
}
