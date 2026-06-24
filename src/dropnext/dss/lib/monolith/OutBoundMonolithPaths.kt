package dropnext.dss.lib.monolith

/**
 * Outbound paths the DSS calls on the DropNext monolith. Appended to
 * `Config.monolithBaseUrl` (+ optional `Config.monolithApiPrefix`) by `HttpMonolithService`.
 *
 * These constants are kept here on purpose. OpenAPI codegen is configured in models-only mode
 * (`apis=false`), so path constants are not generated from the spec.
 */
@Suppress("ConstPropertyName") // Less shouty field names.
object OutBoundMonolithPaths {
  /** `POST /orders` — create a Shopify order in the monolith. */
  const val orders = "/orders"

  /** `GET /stores` — look up a store by `shopify_subdomain`. */
  const val stores = "/stores"

  /** `PUT /stores/api-key` — persist the Shopify Admin token after OAuth install. */
  const val storesApiKey = "/stores/api-key"

  /** `POST|GET|DELETE /product-variants` — upsert / list / soft-delete variants. */
  const val productVariants = "/product-variants"
}
