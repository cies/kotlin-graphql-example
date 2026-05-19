# DSS implementation log

This document records what was implemented for the **DropNext Shopify Service (DSS)** in this repository: OpenAPI-shaped REST, Shopify Admin GraphQL (`2026-04`), **stateless** Admin token resolution (no `stores.json`), monolith order forwarding, fulfillment sync, and security hooks.

## Configuration

- **`DssAppConfig`** (`com.example.lib.dss`): reads `MONOLITH_BASE_URL`, `MONOLITH_API_KEY`, `MONOLITH_CREATE_ORDER_PATH`, `DSS_INTERNAL_SECRET`, `DSS_SHOP_ACCESS_TOKENS`, `SANDBOX_SHOP`, `SANDBOX_ACCESS_TOKEN`, harness flags (see `README.md`).
- **Tokens**: Map `shop.myshopify.com` → Admin API token from env (`DSS_SHOP_ACCESS_TOKENS` as comma-separated `shop|token` pairs), merged with `SANDBOX_*` when the test harness is on. Callers may send **`X-Shopify-Access-Token`** on DSS POSTs instead.

## REST API

- **Canonical OpenAPI:** `openapi.json` (repo root, **3.0.0**): monolith **`paths`** DTO codegen + **`x-webhooks`** for DSS inbound URL contracts. `openApiGenerate` runs with **`skipValidateSpec=false`**; on Windows the spec path is passed as a **`file:` URI** so `$ref` resolution works.
- Readable mirror / docs: **`docs/openapi/dss-api.yaml`** (paths + payloads aligned with `openapi.json`).
- Routing vs handling: `dropnext.dss.lib.dss.DssRouting` (`installDssRoutes`); **`POST /tracking-update`** = sync shipments body (**`SyncShipmentsWithFulfillmentsRequest`**); **`POST /sync-shipments-with-fulfillments`** (and **`/tracking-updates`**) = **`TrackingUpdateRequest`**. **`/dummy1`/`/dummy2`** removed from routing.
- When `DSS_INTERNAL_SECRET` is set, DSS routes require header `X-DSS-Internal-Secret` (see `DssInternalAuth`).

## Monolith client

- **`MonolithCreateOrderPort`** + **`HttpMonolithClient`** in `com.example.lib.monolith` (Ktor **client** only; no server dependency — see `ArchitectureTest`).

## Shopify integration

- **OAuth callback**: after code exchange, runs `ShopIdentity`, sync sample products, registers webhooks; **does not persist tokens** — success page shows a `DSS_SHOP_ACCESS_TOKENS` example line.
- **Webhooks**: require a token in the env map for the shop; otherwise log and return 200 without GraphQL.
- **Fulfillment**: `DssFulfillmentService` — same GraphQL flow as before; tracking uses `FulfillmentEventCreateMutation` with string status parsing (`FulfillmentEventStatusParser`).

## GraphQL documents

- `GetOrderForDss.graphql` (includes `totalPriceSet` for monolith payload), `FulfillmentCancel.graphql`, `FulfillmentCreateWithLineItems.graphql`, `FulfillmentEventCreate.graphql`, `ShopIdentity.graphql` under `src/main/resources/`.

## Demo routes

- `/demo/*` when `ENABLE_DEMO_ROUTES=true` or test harness is on; resolves Admin token from env map like webhooks.

## Notes

- `Shop` and `FulfillmentOrder` do not expose `legacyResourceId` in API `2026-04`; numeric ids are derived from GIDs where needed (`ShopifyGid.kt`).

## Operational / security practices (implemented)

- **Internal API secret:** `X-DSS-Internal-Secret` compared with `MessageDigest.isEqual` (constant-time).
- **Outbound HTTP:** No Ktor client body logging; timeouts configured. Monolith error bodies truncated in exceptions.
- **Errors:** Global handler returns generic “internal error”; webhook logs omit JSON bodies (size only).
