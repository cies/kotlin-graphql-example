# Testing with `FakeMonolithService`

Use the in-memory [`FakeMonolithService`](../src/test/kotlin/dropnext/dss/testing/fake/FakeMonolithService.kt) when code depends on [`MonolithService`](../src/main/kotlin/dropnext/dss/lib/monolith/MonolithService.kt) and you want to assert outbound monolith behavior **without HTTP**.

Do **not** use it for mapper tests, JSON contract tests, or shop-domain helpers — those are pure unit tests.

## Run tests

```powershell
.\gradlew.bat test
```

## Example

[`WebhookMonolithSyncTest`](../src/test/kotlin/dropnext/dss/lib/dss/WebhookMonolithSyncTest.kt) calls [`postMappedOrderToMonolith`](../src/main/kotlin/dropnext/dss/lib/dss/MonolithOrderSync.kt) with a fake and asserts the recorded `CreateShopifyOrderRequest`.

Configure the fake:

- `createOrderStatus` — `200`, `409`, `400`, `500`, …
- `createOrderErrorBody` — JSON for error paths
- `lastCreateOrder` / `createOrderCallCount` — assertions after the call

## Pointing a live DSS process at a stub monolith

| Variable | Purpose |
| -------- | ------- |
| `MONOLITH_BASE_URL` | Base URL (e.g. `http://127.0.0.1:19090` or `https://staging…/api/shopify-service/v1`) |
| `MONOLITH_API_KEY` | Optional `Authorization: Bearer …` |
| `MONOLITH_CREATE_ORDER_PATH` | Default `/orders` |
| `DSS_ALLOW_INSECURE_MONOLITH` | `true` for local `http://` stubs |

See [`DssAppConfig`](../src/main/kotlin/dropnext/dss/lib/dss/DssAppConfig.kt) and [`README.md`](../README.md).
