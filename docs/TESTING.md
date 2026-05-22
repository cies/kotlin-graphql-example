# Testing (DSS)

## Policy (all `test/`)

| Rule | Detail |
| ---- | ------ |
| **Assertions** | Use `assert(condition)` so Power Assert expands failures. Do **not** use `assertEquals` in new tests. |
| **No mock frameworks** | No MockK, Mockito, etc. Use **fakes** (in-memory implementations of our interfaces) and **pure functions**. |
| **Generated DTOs** | Deserialize/serialize OpenAPI-generated types under `dropnext.dss.lib.dss.dto` with `AppJson` / `MonolithJson`. |
| **Layout** | Mirror production packages (`lib/dss`, `lib/monolith`, `shopify`, …). |

## Run tests

From the project root:

```powershell
.\gradlew.bat test
```

End-to-end fulfillment verification (monolith → DSS → Shopify) is documented in [FULFILLMENT_VERIFICATION.md](FULFILLMENT_VERIFICATION.md).

## Test categories

1. **Pure unit** — mappers, `shopMyshopifyHostFromWebhook`, `MonolithErrorBody.parse` (no fakes, no I/O).
2. **Serialization / contract** — example JSON from `openapi.json` ↔ generated DTOs; `CreateShopifyOrderRequest` round-trip via `MonolithJson`.
3. **Business logic with fakes** — `FakeMonolithService` only at the [`MonolithService`](../src/dropnext/dss/lib/monolith/MonolithService.kt) boundary (see [TESTING_WITH_FAKE_SERVICES.md](TESTING_WITH_FAKE_SERVICES.md)).

`ArchitectureTest` (Konsist) guards package-layer dependencies, the no-reflection rule, wildcard imports, and hand-written DTOs under `dropnext.dss.lib.dss.dto` (which must come from `openApiGenerate`).
