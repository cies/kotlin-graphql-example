# Project Notes

## What Changed

### Namespace and Runtime Entrypoints
- Renamed the app namespace from `com.example.app` to `shopify.service.app`.
- Updated runtime entrypoints to match:
  - `build.gradle.kts` main class now points to `shopify.service.app.ShopifyServerKt`.
  - `.vscode/launch.json` run configurations now point to the same main class.
- Kept library/domain packages under `com.example.lib.*` (DSS, monolith, Shopify integration), while the app boundary is now `shopify.service.app`.

### Stateless Runtime Refactor
- Removed in-memory state to enforce stateless behavior:
  - Deleted OAuth in-memory store (`OAuthStateStore`).
  - Deleted in-memory webhook dedupe store (`WebhookIdempotencyStore`) and its test.
- Replaced OAuth state storage with stateless signed token validation:
  - Added signed, expiring OAuth state helper (`OAuthStateToken`).
- Webhook behavior now:
  - Verifies HMAC.
  - Does not perform in-process idempotency dedupe.

### Domain Modeling Improvements
- Improved handling of illegal states:
  - Fulfillment status parsing now returns a sealed result (`Known` / `Unknown`).
  - Unsupported statuses fail explicitly.

### Serialization Consistency
- Normalized serialization style:
  - DTOs use `@Serializable` and `@SerialName(value = "...")`.
  - This includes OAuth payload DTOs.

### Architecture Test Hardening
- Strengthened `ArchitectureTest` rules:
  - No mocking framework imports.
  - No `lateinit` in production code.
  - No mutable production property `var`.
  - DSS routing remains thin/pure (no GraphQL-generated imports in `DssRouting`).

### Documentation Updates
- Updated docs to reflect architecture intent:
  - Stateless app rationale.
  - Fake-over-mock testing stance.
  - Route-vs-handler separation intent.
  - Webhook idempotency notes aligned with stateless runtime behavior.

## Verification
- Test suite executed successfully after the refactor and namespace rename.