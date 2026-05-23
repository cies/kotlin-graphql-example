DropNext Shopify Service
========================

A stateless service that bridges retailer Shopify stores to the DropNext platform (`dropnext-monolith`):
* OAuth install flow,
* catalog/order webhooks,
* typed Admin Graphql calls, and
* forwarding of order events to the [`dropnext-monolith`](../dropnext/dropnext-monolith).


### The stack

The tech stack is minimalistic, optimized for development velocity and AI-readiness.
Its application layer runs on the JVM and primarily uses libraries from the Kotlin ecosystem.

* [Kotlin](https://kotlinlang.org)
* [Ktor](https://ktor.io/) —
Lightweight HTTP server (CIO engine) and client (with OkHttp engine). Used both as the inbound
server (OAuth, webhooks, DSS REST) and as the outbound client (Shopify Admin API, monolith forwarding).
That Kotlin's defacto standard Graphql stack builds on top of KTor is the main reason this project is not
part of `dropnext-monolith` (which builds on `http4k`): to avoid dependency hell.
* [graphql-kotlin](https://github.com/ExpediaGroup/graphql-kotlin) —
Compile-time-typed Graphql client. Queries live as `*.graphql` files in `src/resources/` and the
Gradle plugin generates typed Kotlin classes from them — schema drift fails the build instead of
the producing error at runtime.
* [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) —
Powerful and minimalistic (no reflection) lib for JSON. Used for both inbound Shopify payloads
and outbound monolith DTOs.
* [openapi-generator](https://github.com/OpenAPITools/openapi-generator) —
Generates Kotlin DTOs from [`openapi.json`](./openapi.json) (the canonical `DSS <-> monolith` contract).
Handwritten copies of those DTOs are explicitly forbidden by `ArchitectureTest`.
* [Konsist](https://docs.konsist.lemonappdev.com/) —
Kotlin-native architecture tests; enforces package-layer dependencies and the "no reflection" rule.


### Project goals

* Be a thin, stateless bridge between Shopify and the monolith — no in-memory session/token state.
* Discoverable and functional architecture (CTRL-click yourself to mastery).
* Quick developer cycles (fast recompiles, few dependencies, no JVM reflection).
* 12-factor principles (configuration by env vars).
* Test with **fakes**, not mocks (see [docs/TESTING.md](docs/TESTING.md)).

Non-goals:
* Multi-tenant session storage — the platform boundary is responsible for idempotency and dedup.
* Async/reactive/coroutines beyond what Ktor's CIO engine provides out of the box.


### Code map

All Kotlin lives under `src/dropnext/dss/` (we set `srcDir("src")` in Gradle to skip the Maven `src/main/kotlin/` prefix). Layer rules are enforced by [`ArchitectureTest`](test/dropnext/dss/ArchitectureTest.kt).

| Package | Role |
| ------- | ---- |
| [`app.kt`](src/dropnext/dss/app.kt) | `main` — reads config, builds the dependency graph, starts Ktor. |
| [`config/`](src/dropnext/dss/config) | Env-var parsing, `DssAppConfig` + `ShopifyConfig`, sandbox token map. |
| [`path/`](src/dropnext/dss/path) | URL path constants for inbound DSS routes, outbound monolith routes, and outbound Shopify routes. Single source of truth. |
| [`shopify/`](src/dropnext/dss/shopify) | Shopify-specific helpers: OAuth, HMAC signatures, webhook body parsers, product/order mappers, shop-domain normalization. |
| [`workflow/`](src/dropnext/dss/workflow) | Multi-step orchestration that doesn't touch HTTP types directly (e.g. webhook → load order → map → post to monolith). |
| [`handler/`](src/dropnext/dss/handler) | Ktor handlers — `ApplicationCall → response`. Where validation, auth, and transport concerns live. |
| [`routing/`](src/dropnext/dss/routing) | Thin Ktor route bindings (`installXyzRoutes(handlers)`). One per handler family. |
| [`presentation/`](src/dropnext/dss/presentation) | Pure view layer — data in, HTML out. No Ktor/HTTP types. |
| [`lib/dss/`](src/dropnext/dss/lib/dss) | DSS protocol primitives: `ShopAccessTokenCache`, GID helpers, constant-time compare, generated OpenAPI DTOs under `lib.dss.dto`. |
| [`lib/fulfillment/`](src/dropnext/dss/lib/fulfillment) | Pure fulfillment domain: request validation, fulfillment-order matching, `FulfillmentResult` + status mapping, `DssFulfillmentService`. |
| [`lib/monolith/`](src/dropnext/dss/lib/monolith) | Outbound monolith client: `MonolithService` interface, `HttpMonolithService` impl, error body parsing, structured logging. |
| [`lib/json/`](src/dropnext/dss/lib/json) | Shared `kotlinx.serialization` configs: `AppJson` (inbound) and `MonolithJson` (outbound). |
| [`lib/ktor/`](src/dropnext/dss/lib/ktor) | Ktor server extensions: trace-id MDC interceptor, plain-text error helpers, auth helpers. |
| `src/resources/` | `.graphql` queries (compile-time-typed by the Gradle plugin), `logback.xml`. |
| `src/graphql-schema/` | Committed Shopify Admin schema (regenerated via `./gradlew graphqlIntrospectSchema`). |
| [`openapi.json`](openapi.json) | Canonical DSS ↔ monolith contract. DTOs under `lib.dss.dto` are **generated** from this by `openApiGenerate`. |


### Our preferred IDE

See [docs/setup-intellij-idea.md](./docs/setup-intellij-idea.md) for IntelliJ IDEA setup and troubleshooting.


### Running the project for development

1. Copy `.env.example` to `.env` and fill in the required Shopify credentials.
2. Expose the server with HTTPS (e.g. [ngrok](https://ngrok.com/)) and set `PUBLIC_BASE_URL` to that origin (no trailing slash). Shopify requires HTTPS for OAuth and webhook callbacks.
3. Run the app:

   ```sh
   ./gradlew run
   ```

4. Point your browser to `http://localhost:{PORT}/install?shop=your-dev-store.myshopify.com` to start the OAuth flow.
5. Optional: set `ENABLE_TEST_HARNESS=true` and open `/dev/test-harness` for an interactive page that hits DSS/Demo routes against a sandbox shop (no real Shopify calls when `DSS_SANDBOX_FAKE_SHOPIFY=true`).


### Build deployable containers

```sh
docker build -t dropnext-shopify-service:local .
docker run --rm --env-file .env -p 8080:8080 dropnext-shopify-service:local
```

If you set `PORT` to a different value, publish the same port on both sides (`-p 9090:9090` when `PORT=9090`).

`docker-compose.yml` provides three profiles:

```sh
docker compose up --build app                            # standard
docker compose --profile harness up --build app-harness  # ENABLE_TEST_HARNESS=true
docker compose --profile local-monolith up app-local-monolith  # DSS_ALLOW_INSECURE_MONOLITH=true, dev-only
```


### Codegen

#### Graphql (Shopify Admin API)

The project targets Admin API **`2026-04`**. Keep these in sync when changing versions:

* `graphql.client.endpoint` in `build.gradle.kts`
* `graphql.config.yml` (IDE plugin URL, if present)
* `SHOPIFY_API_VERSION` env var (default in `ShopifyConfig.kt`)

After bumping the version, run `./gradlew graphqlIntrospectSchema graphqlGenerateClient` and fix any schema drift.

The schema is committed at `src/graphql-schema/schema.graphql` (so the IDE plugin can find it).

#### OpenAPI (monolith DTOs)

Monolith request/response bodies are **generated** from [`openapi.json`](openapi.json)
(OpenAPI 3.0, spec validation enabled in Gradle).
Inbound DSS webhook contracts live under `x-webhooks` in the spec (3.0 has no root `webhooks` key).

```sh
./gradlew openApiGenerate
```

* Generator: `kotlin` + `jvm-ktor`
* Output: `build/generated/openapi/.../dropnext/dss/lib/dss/dto/`
* Policy: **Models only** (`apis=false`) — HTTP stays in handwritten `HttpMonolithService`

Do not add handwritten copies of `CreateShopifyOrderRequest` or other spec DTOs.
If codegen fails, fix the schema in `openapi.json`, then re-run `openApiGenerate` (or any compile task).


### The JetBrains [Graphql IDE plugin](https://plugins.jetbrains.com/plugin/8097-graphql)

With this plugin, you get syntax + error highlighting and autocomplete on Graphql queries in IntelliJ IDEA.
It also lets you run queries against an endpoint directly from the IDE.

The plugin's introspection (downloading of the schema) does not work well with the Shopify Graphql API.
To mitigate, we use the Gradle plugin's introspection (the `graphqlIntrospectSchema` task) and
point the IDE plugin at the committed `src/graphql-schema/schema.graphql`.


### Shopify Partner app

* Create or use a Partner app and set **Allowed redirection URL(s)** to exactly `{PUBLIC_BASE_URL}{OAUTH_REDIRECT_PATH}`
(default path `/oauth/callback`).
* Suggested scopes (trim to what your app actually needs; mirror in the Partner Dashboard):
  * `read_products` — catalog sync and product webhooks
  * `read_inventory`, `read_locations` — optional, if you extend inventory sync
  * `write_webhooks` — `webhookSubscriptionCreate` on install
  * `read_orders` — order webhooks and `GetOrderById`
  * `write_merchant_managed_fulfillment_orders` (and matching `read_*`, per [access scopes](https://shopify.dev/docs/api/usage/access-scopes)) — create fulfillments and tracking from the app
* Example: `read_products,read_orders,write_webhooks,write_merchant_managed_fulfillment_orders,read_merchant_managed_fulfillment_orders`


### Hosted deployment

* **App URL**: `PUBLIC_BASE_URL` must be the HTTPS origin Shopify uses to reach this service.
* **Listen address**: binds `0.0.0.0` so it works in containers and typical PaaS hosting.
* **Access tokens**: the server is **stateless** — it does not persist Admin API tokens.
After OAuth, the success page shows how to set `DSS_SHOP_ACCESS_TOKENS` (or use `X-Shopify-Access-Token` on DSS requests).
Webhooks and `/demo/*` resolve the token from that env map for each shop.


### Webhooks registered on install

Subscriptions all use the same HTTPS callback: `{PUBLIC_BASE_URL}/webhooks/shopify`.

* `PRODUCTS_CREATE`, `PRODUCTS_UPDATE`, `PRODUCTS_DELETE`
* `ORDERS_CREATE`, `ORDERS_UPDATED`

On `products/create` and `products/update`, the app parses the webhook body for the resource id and runs `GetProductById`. On `orders/create`, if `MONOLITH_BASE_URL` is set, it runs `GetOrderForDss` and POSTs to the monolith. On `orders/updated`, monolith sync only runs when `DSS_SYNC_ORDER_ON_UPDATED=true` (default off to avoid duplicate POSTs).

**Shop domain:** webhooks use `X-Shopify-Shop-Domain` (forward this header through your reverse proxy). DSS logs include a per-request `trace_id` (Logback MDC) and may return `X-Trace-Id` on responses. Monolith error JSON may include a separate `monolith_trace_id` in WARN logs.


### DSS internal REST

OpenAPI (human-readable mirror): [`docs/openapi/dss-api.yaml`](docs/openapi/dss-api.yaml).
**Canonical:** [`openapi.json`](openapi.json) at repo root (Gradle `openApiGenerate` uses it).

* `POST /sync-shipments-with-fulfillments` — accepts `SyncShipmentsWithFulfillmentsRequest` (sync DropNext shipments ↔ Shopify fulfillments).
* `POST /tracking-update` — accepts `TrackingUpdateRequest` (tracking status → Shopify FulfillmentEvent).
* `POST /tracking-updates` — alias for `/tracking-update`, same payload.
* `PUT /stores/api-key` — accepts `PutShopAccessTokenRequest`; caches the Shopify Admin token in memory and forwards it to the monolith when `MONOLITH_BASE_URL` is set.

Pass `X-Shopify-Access-Token` or configure `DSS_SHOP_ACCESS_TOKENS`. When `DSS_INTERNAL_SECRET` is set, all four routes also require header `X-DSS-Internal-Secret`.


### Security notes (production)

* Use **HTTPS** everywhere between clients, monolith, and this service;
set `DSS_ALLOW_INSECURE_MONOLITH=true` only on developer machines.
* Set `DSS_INTERNAL_SECRET` so internal REST is not open on the network;
the header is compared in **constant time** to reduce timing leaks.
* **Secrets in env**: `DSS_SHOP_ACCESS_TOKENS` and `SANDBOX_ACCESS_TOKEN` are as sensitive as passwords —
use a secrets manager in production, not committed `.env` files.
* The HTTP client does **not** log request bodies (avoids leaking tokens to logs).
Unhandled server errors return a generic message; details stay in server logs only.


### Environment variables

| Variable | Required | Description |
| -------- | -------- | ----------- |
| `SHOPIFY_APP_CLIENT_ID` | yes | App Client ID (OAuth client id used for install flow) |
| `SHOPIFY_APP_CLIENT_SECRET` | yes | App secret (OAuth HMAC, token exchange, webhook HMAC) |
| `SHOPIFY_SCOPES` | yes | Comma-separated scopes (see above) |
| `PUBLIC_BASE_URL` | yes | Public https origin of this server (tunnel URL in dev) |
| `OAUTH_REDIRECT_PATH` | no | Default `/oauth/callback` (must match Partner redirect URL) |
| `SHOPIFY_API_VERSION` | no | Default `2026-04` (keep in sync with `build.gradle.kts` / `graphql.config.yml`) |
| `PORT` | no | Default `8080` |
| `DSS_SHOP_ACCESS_TOKENS` | no | Comma-separated `shop.myshopify.com\|shpat_…` pairs for stateless token lookup (see `ShopAccessTokensEnv.kt`) |
| `MONOLITH_BASE_URL` | no | **REST root URL** DSS appends segments to (`/orders`, `/stores`, `/stores/api-key`, `/product-variants`). May include a path prefix, e.g. `https://staging.dropnext.com/api/shopify-service/v1` (no trailing slash). Leave `MONOLITH_API_PREFIX` empty when the full prefix is already in this value. |
| `MONOLITH_API_PREFIX` | no | Inserted **after** base: `{BASE}/{PREFIX}/stores/api-key`. Example env `MONOLITH_API_PREFIX=api/v1`. Omit slashes at edges; empty (default) uses paths directly under base. |
| `MONOLITH_API_KEY` | no | Optional Bearer token for monolith requests (`Authorization`). |
| `MONOLITH_CREATE_ORDER_PATH` | no | Default `/orders` (relative URL segment after `{BASE}` and prefix) |
| `DSS_INTERNAL_SECRET` | no | If set, DSS REST routes require `X-DSS-Internal-Secret` |
| `DSS_ALLOW_INSECURE_MONOLITH` | no | Set `true` only for local dev so `MONOLITH_BASE_URL` may use `http://`. Production should use `https://` (default: insecure URLs are rejected at startup). |
| `DSS_SYNC_ORDER_ON_UPDATED` | no | Default `false`. When `true`, `orders/updated` webhooks also POST to the monolith (in addition to `orders/create`). |
| `ENABLE_DEMO_ROUTES` | no | Set `true` to expose `/demo/*` without the HTML harness. **If `ENABLE_TEST_HARNESS=true`, demo routes are always turned on** for local testing. |
| `ENABLE_TEST_HARNESS` | no | Set `true` for `/dev/test-harness` and **/demo/* routes**. With harness on, a default fake token is merged for `SANDBOX_SHOP` unless `SANDBOX_ACCESS_TOKEN` is set. |
| `SANDBOX_SHOP` | no | Short handle merged into the token map when the harness is on (default `harness-sandbox`). |
| `SANDBOX_ACCESS_TOKEN` | no | Optional real dev-store Admin token for that sandbox shop; if unset with harness on, a **non-production placeholder** is used. |
| `DSS_SANDBOX_FAKE_SHOPIFY` | no | Only with **`ENABLE_TEST_HARNESS=true`**. If `true`, sync/tracking/demo **skip real Shopify HTTP** and return stub **200** JSON/text so the test page does not hit 500/Graphql throws. Use for local UI checks; never in production. |

Legacy compatibility: `SHOPIFY_API_KEY` and `SHOPIFY_API_SECRET` are still accepted as fallbacks when the new `SHOPIFY_APP_CLIENT_ID` / `SHOPIFY_APP_CLIENT_SECRET` vars are not set.


### Troubleshooting

* **Startup exits quickly**: verify required vars `SHOPIFY_APP_CLIENT_ID` and `SHOPIFY_APP_CLIENT_SECRET` (or legacy `SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET`), plus `SHOPIFY_SCOPES` and `PUBLIC_BASE_URL`.
* **OAuth callback mismatch in Shopify**: ensure `{PUBLIC_BASE_URL}{OAUTH_REDIRECT_PATH}` exactly matches the Partner Dashboard redirect URL.
* **DSS auth failures (`401`)**: provide `X-Shopify-Access-Token` or configure `DSS_SHOP_ACCESS_TOKENS`; include `X-DSS-Internal-Secret` when `DSS_INTERNAL_SECRET` is set.
* **`DSS_SHOP_ACCESS_TOKENS` parse issues**: use comma-separated `shop.myshopify.com|shpat_...` pairs.
* **`[monolith] MONOLITH_BASE_URL is unset` despite being configured**: duplicate `MONOLITH_BASE_URL` / `MONOLITH_API_KEY` lines (often empty trailing blocks pasted from templates) cause **last value wins**. Remove the trailing empties so only one assignment remains; redeploy/restart.
* **`Monolith store api-key … status=404` with HTML `<h1>Not Found`**: DSS hit `{MONOLITH_BASE_URL}/stores/api-key` (before optional prefix). Use the REST API domain (often `api.…`), or set `MONOLITH_API_PREFIX` if routes live under a path (`api/v1`). Confirm with `curl -i -X PUT https://your-api…/stores/api-key` (+ Bearer header) outside DSS.
* **502 Bad Gateway on `PUBLIC_BASE_URL`**: the reverse proxy forwards to the wrong container port. The JVM binds `PORT` (see `[http] Listening …` startup line). Dockerfile sets `ENV PORT=9999`, but dashboards that add an empty `PORT=` override that with blank. Set `PORT=9999` explicitly or remove the `PORT` key so the image default wins; Traefik/nginx must target the **same** port.
* **Insecure monolith URL rejected**: set `DSS_ALLOW_INSECURE_MONOLITH=true` only for local development; production should remain HTTPS.

IDE-specific troubleshooting lives in [docs/setup-intellij-idea.md](./docs/setup-intellij-idea.md).
