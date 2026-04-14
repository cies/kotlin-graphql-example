# Example of using GraphQL from Kotlin

This project is a simple application that uses the
[GraphQL Kotlin Gradle plugin](https://expediagroup.github.io/graphql-kotlin/docs/plugins/gradle-plugin)
to auto-generate GraphQL client data model that's deserialized with `kotlinx.serialization`.
The [Ktor based client](https://github.com/ExpediaGroup/graphql-kotlin/tree/master/clients/graphql-kotlin-ktor-client)
is used to communicate with the Shopify Admin GraphQL API.

It demonstrates:

* Compile-time type checking of GraphQL queries against the schema.
* Proper IDE support for writing queries in IntelliJ IDEA (syntax + error highlighting, and autocomplete).
* Kotlin project (no TypeScript or JavaScript).

It makes use of Shopify's GraphQL API, which has a huge schema (more about the challenges that it posed below).


## The JetBrains [GraphQL IDE plugin](https://plugins.jetbrains.com/plugin/8097-graphql)

With this plugin, you get syntax + error highlighting and autocomplete on GraphQL queries in IntelliJ IDEA.
It also allows you to run queries against an endpoint directly from the IDE.

It seems, though, that the plugin "introspection" (downloading of the schema) does not work well with the Shopify GraphQL API.
Strange enough, it did work with the Pokémon GraphQL API.

To mitigate this, we use the Gradle plugin's introspection feature (the `graphqlIntrospectSchema` task) to fetch the schema.
The default location (in `build/`) was not accessible to the IDE plugin,
so the Gradle GraphQL plugin is configured to put it in `src/main/graphql-schema`.
Using `src/main/graphql.config.yml` we configure the IDE plugin to look for the schema there.


## Building locally

This project uses Gradle and you can build locally using

```shell script
gradle clean build
```

Codegen and IDE tooling use the public Shopify Admin schema proxy (`https://shopify.dev/admin-graphql-direct-proxy/2026-01`); no access token is required for `graphqlIntrospectSchema` or `graphqlGenerateClient`.

At runtime, the app calls the **shop-specific** endpoint `https://{shop}/admin/api/{version}/graphql.json` with the access token.

## Shopify Partner app: OAuth, Admin GraphQL, webhooks, orders, fulfillment

The runnable app is a **Ktor server** (`com.example.ShopifyServerKt`) that implements the [authorization code grant](https://shopify.dev/docs/apps/auth/oauth/getting-started), syncs catalog pages with variants, registers product and order webhooks, loads full **order** data when webhooks fire, and exposes demo endpoints to create fulfillments and update tracking. Incoming webhooks are verified with `X-Shopify-Hmac-Sha256`.

### API version (January release track)

The project targets Admin API **`2026-01`**. Keep these in sync when you change versions:

* `graphql.client.endpoint` in `build.gradle.kts`
* `graphql.config.yml` (IDE plugin URL)
* Optional env `SHOPIFY_API_VERSION` (defaults to `2026-01` in `ShopifyConfig`)

After changing the version, run `./gradlew graphqlIntrospectSchema graphqlGenerateClient` and fix any schema drift.

### Partner Dashboard

Create or use a Partner app, set **Allowed redirection URL(s)** to exactly:

`{PUBLIC_BASE_URL}{OAUTH_REDIRECT_PATH}` (default path `/oauth/callback`).

For local development, expose the server with **HTTPS** (e.g. [ngrok](https://ngrok.com/)) and set `PUBLIC_BASE_URL` to that origin (no trailing slash).

### Hosted deployment

* **App URL**: `PUBLIC_BASE_URL` must be the HTTPS origin Shopify uses to reach your app (production domain or tunnel). It is used for OAuth redirects and webhook callback URLs.
* **Listen address**: The server binds to `0.0.0.0` so it works in containers and typical PaaS hosting.
* **Access tokens**: Tokens are stored **in memory** in `AccessTokenStore`. A single process is fine for demos; for production or **multiple instances**, persist tokens (database or secure store) so OAuth and webhook-triggered Admin API calls still work after restarts or on another node.

### Environment variables

| Variable | Required | Description |
| -------- | -------- | ----------- |
| `SHOPIFY_API_KEY` | yes | App API key (Client ID) |
| `SHOPIFY_API_SECRET` | yes | App secret (OAuth HMAC, token exchange, webhook HMAC) |
| `SHOPIFY_SCOPES` | yes | Comma-separated scopes (see below) |
| `PUBLIC_BASE_URL` | yes | Public https origin of this server (tunnel URL in dev) |
| `OAUTH_REDIRECT_PATH` | no | Default `/oauth/callback` (must match Partner redirect URL) |
| `SHOPIFY_API_VERSION` | no | Default `2026-01` (keep in sync with `build.gradle.kts` / `graphql.config.yml`) |
| `PORT` | no | Default `8080` |

**Suggested scopes** (trim to what your app needs; configure the same list in the Partner Dashboard):

* `read_products` - catalog sync and product webhooks
* `read_inventory`, `read_locations` - optional, if you extend inventory sync
* `write_webhooks` - `webhookSubscriptionCreate` on install
* `read_orders` - order webhooks and `GetOrderById`
* `write_merchant_managed_fulfillment_orders` (and the matching `read_*` scope for fulfillment orders, per [access scopes](https://shopify.dev/docs/api/usage/access-scopes)) - create fulfillments and tracking from the app; exact names may vary by fulfillment setup

Example:

`read_products,read_orders,write_webhooks,write_merchant_managed_fulfillment_orders,read_merchant_managed_fulfillment_orders`

### Webhooks registered on install

Subscriptions use the same HTTPS callback: `{PUBLIC_BASE_URL}/webhooks/shopify`.

* `PRODUCTS_CREATE`, `PRODUCTS_UPDATE`, `PRODUCTS_DELETE`
* `ORDERS_CREATE`, `ORDERS_UPDATED`

On `products/create` and `products/update`, the app parses the webhook body for the resource id and runs `GetProductById`. On `orders/create` and `orders/updated`, it runs `GetOrderById`.

### Run

```shell script
gradle run
```

1. Open `http://localhost:{PORT}/install?shop=your-dev-store.myshopify.com` (use the HTTP port Ktor listens on).
2. Finish Shopify OAuth; the success page shows a sample catalog sync and webhook registration logs per topic.
3. Change a product or create an order in the dev store; check server logs for verified webhooks and follow-up GraphQL fetches.
4. `GET /demo/products?shop=...` - paginated products with variants (`first`, optional `after` cursor).
5. `GET /demo/order?shop=...&id=` - full order by GID or numeric id.
6. **Fulfillment demos** (JSON body, `Content-Type: application/json`):
   * `POST /demo/fulfillment/create` - body: `shop`, `fulfillmentOrderId`, `trackingNumber`, optional `company`, `trackingUrl`, `notifyCustomer` (uses Admin `fulfillmentCreate` with tracking).
   * `POST /demo/fulfillment/tracking` - body: `shop`, `fulfillmentId`, `trackingNumber`, optional `company`, `trackingUrl`, `notifyCustomer` (uses `fulfillmentTrackingInfoUpdate`).

These demo POST endpoints are not authenticated beyond knowing a shop that has completed install; protect or remove them in production.

### Notes

* If webhook registration returns user errors (e.g. duplicate subscription), check logs; delivery may still work for existing subscriptions.
* GraphQL `URL` scalar handling uses the GraphQL Kotlin client defaults (Kotlin `String` typealias).
* Order webhooks can be delivered more than once; add idempotency if you add side effects beyond logging.
