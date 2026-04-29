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

Codegen and IDE tooling use the public Shopify Admin schema proxy (`https://shopify.dev/admin-graphql-direct-proxy/2026-04`); no access token is required for `graphqlIntrospectSchema` or `graphqlGenerateClient`.

At runtime, the app calls the **shop-specific** endpoint `https://{shop}/admin/api/{version}/graphql.json` with the access token.

## Shopify Partner app: OAuth, Admin GraphQL, webhooks, orders, fulfillment

The runnable app is a **Ktor server** (`com.example.ShopifyServerKt`) that implements the [authorization code grant](https://shopify.dev/docs/apps/auth/oauth/getting-started), syncs catalog pages with variants, registers product and order webhooks, loads full **order** data when webhooks fire, optional **DSS** internal REST (stores, variants, fulfillment sync, tracking), optional **monolith** forwarding on `orders/create`, and (when enabled) demo endpoints to create fulfillments and update tracking. Incoming webhooks are verified with `X-Shopify-Hmac-Sha256`.

### API version

The project targets Admin API **`2026-04`**. Keep these in sync when you change versions:

* `graphql.client.endpoint` in `build.gradle.kts`
* `graphql.config.yml` (IDE plugin URL)
* Optional env `SHOPIFY_API_VERSION` (defaults to `2026-04` in `ShopifyConfig`)

After changing the version, run `./gradlew graphqlIntrospectSchema graphqlGenerateClient` and fix any schema drift.

### Partner Dashboard

Create or use a Partner app, set **Allowed redirection URL(s)** to exactly:

`{PUBLIC_BASE_URL}{OAUTH_REDIRECT_PATH}` (default path `/oauth/callback`).

For local development, expose the server with **HTTPS** (e.g. [ngrok](https://ngrok.com/)) and set `PUBLIC_BASE_URL` to that origin (no trailing slash).

### Hosted deployment

* **App URL**: `PUBLIC_BASE_URL` must be the HTTPS origin Shopify uses to reach your app (production domain or tunnel). It is used for OAuth redirects and webhook callback URLs.
* **Listen address**: The server binds to `0.0.0.0` so it works in containers and typical PaaS hosting.
* **Access tokens**: The server is **stateless** — it does not persist Admin API tokens. After OAuth, the success page shows how to set **`DSS_SHOP_ACCESS_TOKENS`** (or use **`X-Shopify-Access-Token`** on DSS requests). Webhooks and `/demo/*` resolve the token from that env map for each shop.

### Environment variables

| Variable | Required | Description |
| -------- | -------- | ----------- |
| `SHOPIFY_API_KEY` | yes | App API key (Client ID) |
| `SHOPIFY_API_SECRET` | yes | App secret (OAuth HMAC, token exchange, webhook HMAC) |
| `SHOPIFY_SCOPES` | yes | Comma-separated scopes (see below) |
| `PUBLIC_BASE_URL` | yes | Public https origin of this server (tunnel URL in dev) |
| `OAUTH_REDIRECT_PATH` | no | Default `/oauth/callback` (must match Partner redirect URL) |
| `SHOPIFY_API_VERSION` | no | Default `2026-04` (keep in sync with `build.gradle.kts` / `graphql.config.yml`) |
| `PORT` | no | Default `8080` |
| `DSS_SHOP_ACCESS_TOKENS` | no | Comma-separated `shop.myshopify.com\|shpat_…` pairs for stateless token lookup (see `ShopAccessTokensEnv.kt`) |
| `MONOLITH_BASE_URL` | no | If set, `orders/create` webhook POSTs `CreateShopifyOrderRequest` JSON to `{BASE}{MONOLITH_CREATE_ORDER_PATH}` |
| `MONOLITH_API_KEY` | no | Optional Bearer token for monolith requests |
| `MONOLITH_CREATE_ORDER_PATH` | no | Default `/orders` |
| `DSS_INTERNAL_SECRET` | no | If set, DSS REST routes require `X-DSS-Internal-Secret` |
| `DSS_ALLOW_INSECURE_MONOLITH` | no | Set `true` only for local dev so `MONOLITH_BASE_URL` may use `http://`. Production should use `https://` (default: insecure URLs are rejected at startup). |
| `ENABLE_DEMO_ROUTES` | no | Set `true` to expose `/demo/*` without the HTML harness. **If `ENABLE_TEST_HARNESS=true`, demo routes are always turned on** for local testing. |
| `ENABLE_TEST_HARNESS` | no | Set `true` for `/dev/test-harness` and **/demo/* routes**. With harness on, a default fake token is merged for `SANDBOX_SHOP` unless `SANDBOX_ACCESS_TOKEN` is set. |
| `SANDBOX_SHOP` | no | Short handle merged into the token map when the harness is on (default `harness-sandbox`). |
| `SANDBOX_ACCESS_TOKEN` | no | Optional real dev-store Admin token for that sandbox shop; if unset with harness on, a **non-production placeholder** is used. |
| `DSS_SANDBOX_FAKE_SHOPIFY` | no | Only with **`ENABLE_TEST_HARNESS=true`**. If `true`, sync/tracking/demo **skip real Shopify HTTP** and return stub **200** JSON/text so the test page does not hit 500/GraphQL throws. Use for local UI checks; never in production. |

### Security notes (production)

* Use **HTTPS** everywhere between clients, monolith, and this service; set `DSS_ALLOW_INSECURE_MONOLITH=true` only on developer machines.
* Set **`DSS_INTERNAL_SECRET`** so internal REST is not open on the network; the header is compared in **constant time** to reduce timing leaks.
* **Secrets in env**: `DSS_SHOP_ACCESS_TOKENS` and `SANDBOX_ACCESS_TOKEN` are as sensitive as passwords — use a secrets manager in production, not committed `.env` files.
* The HTTP client does **not** log request bodies (avoids leaking tokens to logs). Unhandled server errors return a generic message; details stay in server logs only.

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

On `products/create` and `products/update`, the app parses the webhook body for the resource id and runs `GetProductById`. On `orders/create`, if `MONOLITH_BASE_URL` is set, it runs `GetOrderForDss` and POSTs to the monolith; otherwise it loads with `GetOrderById` and logs. On `orders/updated`, it runs `GetOrderById` and logs.

### DSS internal REST

OpenAPI: `docs/openapi/dss-api.yaml`. Implementation summary: `docs/DSS_IMPLEMENTATION_LOG.md`. Endpoints are **fulfillment-only** (stateless): `/sync-shipments-with-fulfillments`, `/tracking-updates`, `/tracking-update`, and dummy routes **`POST /dummy1`** = tracking payload, **`POST /dummy2`** = sync payload (per monolith OpenAPI / http4k#1516). Pass **`X-Shopify-Access-Token`** or configure **`DSS_SHOP_ACCESS_TOKENS`**.

### Run

```shell script
gradle run
```

With **`ENABLE_TEST_HARNESS=true`**, open `http://localhost:{PORT}/dev/test-harness` for health, DSS fulfillment paths, and **demo** routes (`/demo/*` is included automatically). Use the page’s **Shopify access token** field or env for Admin API calls.

1. Open `http://localhost:{PORT}/install?shop=your-dev-store.myshopify.com` (use the HTTP port Ktor listens on).
2. Finish Shopify OAuth; the success page shows a sample catalog sync and webhook registration logs per topic.
3. Change a product or create an order in the dev store; check server logs for verified webhooks and follow-up GraphQL fetches.
4. **Demo routes** (on when **`ENABLE_DEMO_ROUTES=true`** *or* **`ENABLE_TEST_HARNESS=true`**): `GET /demo/products?shop=...` — paginated products with variants (`first`, optional `after` cursor).
5. `GET /demo/order?shop=...&id=` — full order by GID or numeric id.
6. **Fulfillment demos** (JSON body, `Content-Type: application/json`):
   * `POST /demo/fulfillment/create` — body: `shop`, `fulfillmentOrderId`, `trackingNumber`, optional `company`, `trackingUrl`, `notifyCustomer` (uses Admin `fulfillmentCreate` with tracking).
   * `POST /demo/fulfillment/tracking` — body: `shop`, `fulfillmentId`, `trackingNumber`, optional `company`, `trackingUrl`, `notifyCustomer` (uses `fulfillmentTrackingInfoUpdate`).

Demo routes are off by default; they are not authenticated beyond knowing an installed shop — keep them disabled in production unless you add your own protection.

### Notes

* If webhook registration returns user errors (e.g. duplicate subscription), check logs; delivery may still work for existing subscriptions.
* GraphQL `URL` scalar handling uses the GraphQL Kotlin client defaults (Kotlin `String` typealias).
* Order webhooks can be delivered more than once; add idempotency if you add side effects beyond logging.
