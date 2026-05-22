---
paths:
  - "src/resources/*.graphql"
  - "src/dropnext/dss/shopify/**/*.kt"
---
# Rules for Shopify Graphql queries and codegen


## Query files

- All `.graphql` query files live in `src/resources/` (flat, no subdirectories).
- One query per file. File name = operation name (e.g. `GetOrderForDss.graphql` defines `query GetOrderForDss { ... }`).
- The [`graphql-kotlin`](https://github.com/ExpediaGroup/graphql-kotlin) Gradle plugin generates typed Kotlin classes into `dropnext.graphql.generated.*` at compile time.


## Schema sync

- The Shopify Admin schema is cached at `src/graphql-schema/schema.graphql` (committed to git, not generated on every build).
- After bumping `SHOPIFY_API_VERSION`, re-run:

  ```bash
  ./gradlew graphqlIntrospectSchema graphqlGenerateClient
  ```

  and fix any schema drift in the affected queries.

- Keep these three in sync when changing API version:
  - `graphql.client.endpoint` in `build.gradle.kts`
  - `graphql.config.yml` (IDE plugin URL, if present)
  - `SHOPIFY_API_VERSION` env var (default in `ShopifyConfig.kt`)


## Per-shop clients

Graphql calls are made against the **shop-specific** endpoint `https://{shop}/admin/api/{version}/graphql.json` with a per-shop Admin access token. Use `GraphqlClientCache` to retrieve a client — do not construct one ad hoc.


## Webhook handling

- Inbound webhooks are verified with `X-Shopify-Hmac-Sha256` in **constant time** (`SecureCompare.kt`).
- Always validate HMAC before parsing the body.
- Read the shop domain from `X-Shopify-Shop-Domain` header (reverse proxies must forward it).
- Inbound webhook wiring is in `routing/WebhookRouting.kt`; per-topic dispatch happens in `handler/WebhookHandlers.kt` (matches on `ShopifyWebhookTopic`). Outbound subscription registration on install lives in `shopify/ShopifyWebhookRegistration.kt`.
