# Testing with fake services (no mocks/stubs)

When the monolith (main backend) is offline, you can still verify **outbound** behavior from this app: the JUnit test [`FakeServicesTest`](../src/test/kotlin/com/example/test/FakeServicesTest.kt) starts an in-process **fake monolith** ([`FakeMonolithService`](../src/test/kotlin/com/example/testing/fake/FakeMonolithService.kt)) that implements `POST /orders` the same way [`HttpMonolithClient`](../src/main/kotlin/com/example/lib/monolith/HttpMonolithClient.kt) calls it.

This project standard is simple: use fakes for behavior verification, and avoid mocking/stubbing frameworks.

- **Run tests:** from the project root, run `./gradlew test`. On **Windows PowerShell** use `.\gradlew.bat test` (the `.\` is required; otherwise PowerShell will not find the script). In **cmd.exe**, `gradlew.bat test` is fine.
- **Cleanup later:** delete `src/test/kotlin/com/example/testing/fake/` and the test class, or keep them as a cheap contract check.

---

## Pointing a live DSS process at a fake monolith

Set environment variables (see [`DssAppConfig`](../src/main/kotlin/com/example/lib/dss/DssAppConfig.kt)) so this app’s HTTP **client** talks to a stub instead of production:

| Variable | Purpose |
| -------- | ------- |
| `MONOLITH_BASE_URL` | Base URL of the monolith, e.g. `http://127.0.0.1:19090` (must be `https://` in production unless you opt in to HTTP). |
| `MONOLITH_API_KEY` | Optional. If set, requests include `Authorization: Bearer …`. |
| `MONOLITH_CREATE_ORDER_PATH` | Path appended to the base (default `/orders`). |
| `DSS_ALLOW_INSECURE_MONOLITH` | Set to `true` to allow `http://` for `MONOLITH_BASE_URL` (local fakes and WireMock). |

### Docker variant

If you are running the app in Docker/Compose, use the same env vars through `.env` + `docker compose`. See [`README.md`](../README.md#docker-workflow) for image build and profile commands (`app`, `app-harness`, `app-local-monolith`).

For a **separate** fake process, run any stub that returns `200` and JSON `{"shopify_order_id": <number>}` for `POST` on your chosen path (see `CreateOrderResponse` in [`DssApiDtos`](../src/main/kotlin/com/example/lib/dss/dto/DssApiDtos.kt)).

---

## Calling this app’s HTTP API (DSS) — post data

The server bind address and port come from [`ShopifyConfig`](../src/main/kotlin/com/example/config/ShopifyConfig.kt) (`PORT`, default `8080`). DSS routes require **`X-Shopify-Access-Token`** (or a matching entry in **`DSS_SHOP_ACCESS_TOKENS`**) and, when set, **`DSS_INTERNAL_SECRET`** as `X-DSS-Internal-Secret`.

**Example** — sync fulfillments:

```bash
curl -sS -X POST "http://127.0.0.1:8080/sync-shipments-with-fulfillments" \
  -H "Content-Type: application/json" \
  -H "X-DSS-Internal-Secret: your-secret" \
  -H "X-Shopify-Access-Token: shpat_..." \
  -d @path/to/body.json
```

Full request/response schemas are in [`docs/openapi/dss-api.yaml`](openapi/dss-api.yaml).

---

## OpenAPI `dss-api.yaml` in the test harness

With **`ENABLE_TEST_HARNESS=true`**, open **`/dev/test-harness`**. The UI runs **fulfillment** DSS paths (implementation: [`DssRouting`](../src/main/kotlin/com/example/lib/dss/DssRouting.kt) + [`DssHttpHandlers`](../src/main/kotlin/com/example/lib/dss/DssHttpHandlers.kt)).

| Path | Method | In harness |
| ---- | ------ | ------------ |
| `/sync-shipments-with-fulfillments` | POST | Yes |
| `/dummy2` | POST | Yes (alias of sync) |
| `/tracking-updates` | POST | Yes |
| `/tracking-update` | POST | Yes (shorter name in spec) |
| `/dummy1` | POST | Yes (alias of tracking) |

Use **Run full DSS (OpenAPI)** to hit the above in order. **401** means missing Admin token (add token field on the page or env). **400** from Shopify with the default harness fake token is expected until you set a real **`SANDBOX_ACCESS_TOKEN`**.

For **end-to-end 200s without a real token**, set **`DSS_SANDBOX_FAKE_SHOPIFY=true`** together with **`ENABLE_TEST_HARNESS`** (only).

---

## In-process fake vs full stack

- **In-process (`FakeMonolithService`):** no separate process; use only from tests. Does not start the main Ktor app.
- **End-to-end:** run the app with `MONOLITH_BASE_URL` aimed at a local stub and exercise webhooks/REST. Use the [README](../README.md) for startup and required variables.
