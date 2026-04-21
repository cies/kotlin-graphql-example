# DSS implementation log

This document records what was implemented for the **DropNext Shopify Service (DSS)** in this repository: OpenAPI-shaped REST, Shopify Admin GraphQL (`2026-04`), file-backed store tokens, monolith order forwarding, fulfillment sync, and security hooks.

## Configuration

- **`DssAppConfig`** (`com.example.dss`): reads `MONOLITH_BASE_URL`, `MONOLITH_API_KEY`, `MONOLITH_CREATE_ORDER_PATH`, `DSS_INTERNAL_SECRET`, `DSS_DATA_DIR`, `ENABLE_DEMO_ROUTES` (see `README.md`).
- **Persistence**: `FileStoreRepository` writes `stores.json` under `DSS_DATA_DIR` (default `./data`).

## REST API

- Canonical OpenAPI: `docs/openapi/dss-api.yaml` (aligned with monolith contract: title **DropNext Shopify Service API**, `v1`).
- Kotlin routes: `com.example.dss.DssRoutes` - `GET /stores`, **`PUT /stores/api-key`** (canonical; **`PUT /stores`** kept as backward-compatible alias), `GET`/`POST`/`DELETE` `/product-variants`, `POST` `/sync-shipments-with-fulfillments`, `POST` `/tracking-updates`, `POST` `/tracking-update`, and dummy routes **`POST /dummy1`** = **TrackingUpdatePayload**, **`POST /dummy2`** = **SyncShipmentsWithFulfillmentsPayload** (matches attached OpenAPI / http4k#1516 - not swapped).
- `PUT /stores/api-key` updates an **existing** store only (`FileStoreRepository.updateApiKeyForExistingStoreOnly`); OAuth install continues to use `upsert`.
- When `DSS_INTERNAL_SECRET` is set, DSS routes require header `X-DSS-Internal-Secret` (see `DssInternalAuth`).

## Shopify integration

- **OAuth callback**: after code exchange, runs `ShopIdentity`, resolves numeric shop id from `shop.id` GID, normalizes `myshopifyDomain`, and **upserts** the access token via `FileStoreRepository`.
- **Webhooks**: `orders/create` - if `MONOLITH_BASE_URL` is set, loads the order with `GetOrderForDss`, maps to `CreateShopifyOrderRequest` (`MonolithOrderMapper`), and POSTs via `MonolithClient`. Otherwise logs with `GetOrderById` as before. `orders/updated` continues to log via `GetOrderById`.
- **Fulfillment**: `DssFulfillmentService` - `fulfillmentCancel` for replace ids, then `FulfillmentCreateWithLineItems` per new fulfillment; tracking uses `FulfillmentEventCreateMutation` with string status parsing (`FulfillmentEventStatusParser`).

## GraphQL documents

- `GetOrderForDss.graphql`, `FulfillmentCancel.graphql`, `FulfillmentCreateWithLineItems.graphql`, `FulfillmentEventCreate.graphql`, `ShopIdentity.graphql` under `src/main/resources/`.

## Demo routes

- `/demo/*` is registered only when `ENABLE_DEMO_ROUTES=true`.

## Notes

- `Shop` and `FulfillmentOrder` do not expose `legacyResourceId` in API `2026-04`; numeric ids are derived from GIDs where needed (`ShopifyGid.kt`).

## Operational / security practices (implemented)

- **Internal API secret:** `X-DSS-Internal-Secret` compared with `MessageDigest.isEqual` (constant-time).
- **Outbound HTTP:** No Ktor client body logging; timeouts configured (`HttpTimeout` + OkHttp). Monolith error bodies truncated in exceptions (avoid huge payloads in logs).
- **Persistence:** `stores.json` written via temp file + atomic replace when possible; all store mutations serialized on one lock to avoid lost updates.
- **Errors:** Global handler returns generic “internal error”; webhook logs omit JSON bodies (size only) to reduce PII in logs.
- **Monolith URL:** `https` required unless `DSS_ALLOW_INSECURE_MONOLITH=true` (local dev).
