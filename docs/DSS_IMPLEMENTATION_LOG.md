# DSS implementation log

This document records what was implemented for the **DropNext Shopify Service (DSS)** in this repository: OpenAPI-shaped REST, Shopify Admin Graphql (`2026-04`), **stateless** Admin token resolution (no `stores.json`), monolith order forwarding, fulfillment sync, and security hooks.

## Configuration

- **`Config`** (`dropnext.dss.config`): composes `ShopifyConfig`, `MonolithConfig`, `DevConfig`, `WebhookConfig` plus `monolithWebhookAuthSecret`. Reads `MONOLITH_BASE_URL`, `MONOLITH_API_KEY`, `MONOLITH_CREATE_ORDER_PATH`, `DSS_API_KEY`, `DSS_SHOP_ACCESS_TOKENS`, `SANDBOX_SHOP`, `SANDBOX_ACCESS_TOKEN`, harness flags (see `README.md`).
- **Tokens**: Map `shop.myshopify.com` → Admin API token resolved server-side. Cache is seeded from env (`DSS_SHOP_ACCESS_TOKENS` as comma-separated `shop|token` pairs, merged with `SANDBOX_*` when the test harness is on), from the OAuth callback after a successful install, and from `PUT /stores/api-key` calls. A miss falls back to `MonolithService.getStore`. Tokens are never read from inbound request headers.

## REST API

- **Canonical OpenAPI:** `openapi.json` (repo root, **3.0.0**): monolith **`paths`** DTO codegen + **`x-webhooks`** for DSS inbound URL contracts. `openApiGenerate` runs with **`skipValidateSpec=false`**; on Windows the spec path is passed as a **`file:` URI** so `$ref` resolution works.
- Readable mirror / docs: **`docs/openapi/dss-api.yaml`** (paths + payloads aligned with `openapi.json`).
- Routing vs handling: monolith-webhook routes wired by `dropnext.dss.routing.installMonolithWebhookRoutes`; **`POST /sync-shipments-with-fulfillments`** = **`SyncShipmentsWithFulfillmentsRequest`** → `MonolithWebhookHandlers.handleSyncShipments`; **`POST /tracking-update`** (and alias **`/tracking-updates`**) = **`TrackingUpdateRequest`** → `MonolithWebhookHandlers.handleTrackingUpdate`. **`/dummy1`/`/dummy2`** removed from routing.
- When `DSS_API_KEY` is set, monolith-webhook routes require header `X-DSS-Internal-Secret` (see `installMonolithWebhookAuthSecret` in `lib/ktor/plugin/`).

## Monolith client

- **`MonolithService`** interface + **`HttpMonolithService`** in `dropnext.dss.lib.monolith` (Ktor **client** only; no server dependency — see `ArchitectureTest`). `FakeMonolithService` is the in-memory recording test double.

## Shopify integration

- **OAuth callback**: after code exchange, runs `ShopIdentity`, syncs sample products, calls `HttpShopifyGraphqlService.registerStandardWebhooks`, and persists the Admin token to the monolith via `MonolithService.putStoreApiKey` (the install success page shows the persist outcome).
- **Webhooks**: `ShopifyWebhookHandlers` resolves the Admin token through `ShopifyGraphqlServiceFactory.forShop` (env-map → monolith fallback); when no token is resolvable the handler logs and returns 200 without Graphql.
- **Fulfillment**: `HttpShopifyGraphqlService.syncShipmentsWithFulfillments` / `.createTrackingEvent` — same Graphql flow as before; tracking uses `FulfillmentEventCreateMutation` with string status parsing (`ParsedFulfillmentStatus.parseFulfillmentEventStatus` in `lib/shopify/graphql/fulfillment/`).

## Graphql documents

- `GetOrderForDss.graphql` (includes `totalPriceSet` for monolith payload), `FulfillmentCancel.graphql`, `FulfillmentCreateWithLineItems.graphql`, `FulfillmentEventCreate.graphql`, `ShopIdentity.graphql` under `src/resources/`.

## Demo routes

- `/demo/*` when `ENABLE_DEMO_ROUTES=true` or test harness is on; resolves Admin token from env map like webhooks.

## Notes

- `Shop` and `FulfillmentOrder` do not expose `legacyResourceId` in API `2026-04`; numeric ids are derived from GIDs where needed (`ShopifyGid.kt`).

## Operational / security practices (implemented)

- **Internal API secret:** `X-DSS-Internal-Secret` compared with `MessageDigest.isEqual` (constant-time).
- **Outbound HTTP:** No Ktor client body logging; timeouts configured. Monolith error bodies truncated in exceptions.
- **Errors:** Global handler returns generic “internal error”; webhook logs omit JSON bodies (size only).
