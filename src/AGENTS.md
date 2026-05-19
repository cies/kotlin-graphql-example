# DSS (Kotlin / Ktor) — agent notes

This repo is the **DropNext Shopify Service (DSS)**: Ktor server, Shopify Admin GraphQL client, optional monolith HTTP client.

## Packages

- `dropnext.dss` — server bootstrap, routing entry, JSON config
- `dropnext.dss.lib.dss` — fulfillment handlers, order mapping/sync, config
- `dropnext.dss.lib.monolith` — `MonolithService` + `HttpMonolithService`
- `dropnext.dss.shopify` — OAuth, webhooks, signatures
- `dropnext.dss.lib.dss.dto` — **OpenAPI-generated only** (`./gradlew openApiGenerate`); do not hand-write DTOs here

## Testing

See [docs/TESTING.md](../docs/TESTING.md): `assert()` + Power Assert, no `assertEquals`, no mock frameworks. Use `FakeMonolithService` only at the monolith boundary.

## Monolith contract

- Order POST body: generated `CreateShopifyOrderRequest` only; `fulfillment_status` is `null` when unfulfilled.
- Logs: DSS `trace_id` via Logback MDC; monolith failures log `monolith_trace_id=` when present. Never log tokens or full bodies.
