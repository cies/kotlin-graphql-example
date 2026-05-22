# Fulfillment verification (DSS)

Checklist for verifying **supplier shipment → Shopify fulfillment** via the monolith → DSS path. DSS does not call the Supplier API directly; the monolith bridges supplier events to DSS webhooks defined in [`openapi.json`](../openapi.json) (`x-webhooks`).

## Prerequisites

- Shopify app scopes include `write_merchant_managed_fulfillment_orders` (and read fulfillment orders).
- Monolith and DSS share `DSS_INTERNAL_SECRET` when configured.
- Shopify Admin token available to DSS via `DSS_SHOP_ACCESS_TOKENS` or monolith `X-Shopify-Access-Token` header.
- `MONOLITH_BASE_URL` set on DSS for order/product webhooks (Shopify → monolith).

## Path reference

| Monolith intent | DSS URL | Request body |
|-----------------|---------|--------------|
| Supplier created/reorganized shipment | `POST /sync-shipments-with-fulfillments` | `SyncShipmentsWithFulfillmentsRequest` |
| Carrier tracking status (e.g. AfterShip) | `POST /tracking-update` | `TrackingUpdateRequest` |
| Compat alias for tracking status | `POST /tracking-updates` | `TrackingUpdateRequest` |

See also [`docs/openapi/dss-api.yaml`](openapi/dss-api.yaml).

## Step A — Order ingest (Shopify → monolith)

1. Place a test order on the staging shop (variant-backed line items).
2. Confirm DSS logs: webhook verified → `Monolith create order accepted`.
3. Confirm monolith order has correct `product_variant_id` and **non-zero** `fulfillment_order_id` on each line.

## Step B — Supplier shipment → Shopify fulfill

1. Create a supplier shipment in the monolith for that order.
2. Monolith POSTs to `{DSS_URL}/sync-shipments-with-fulfillments` with a body like:

```json
{
  "shopify_subdomain": "acme-downtown",
  "shopify_order_id": 1001,
  "shipments": [{
    "tracking_number": "1Z999AA10123456784",
    "carrier": "UPS",
    "tracking_url": "https://www.ups.com/track?tracknum=1Z999AA10123456784",
    "line_items": [{ "product_variant_id": 101, "quantity": 1 }]
  }]
}
```

3. Expect **HTTP 200** and `new_fulfillment_ids` non-empty.
4. In Shopify Admin: order shows fulfillment with tracking; line items marked fulfilled.

**Failure checks (after defensive validation):**

- Wrong `product_variant_id` → **404** with explicit message.
- Quantity greater than remaining on FO → **400**.
- Missing `X-Shopify-Access-Token` / env token → **401**.

## Step C — Tracking event (AfterShip-style)

1. Monolith POSTs to `{DSS_URL}/tracking-update` with the **same** `tracking_number` as step B:

```json
{
  "shopify_subdomain": "acme-downtown",
  "shopify_order_id": 1001,
  "tracking_number": "1Z999AA10123456784",
  "status": "in_transit",
  "happened_at": "2026-04-02T08:30:00Z"
}
```

2. Expect **HTTP 200** and `fulfillment_event_id`.
3. Shopify fulfillment timeline shows the event (e.g. in transit).

## Local sandbox (no real Shopify)

```powershell
$env:DSS_SANDBOX_FAKE_SHOPIFY="true"
.\gradlew.bat run
```

POST `/sync-shipments-with-fulfillments` with the step B JSON → fake `new_fulfillment_ids`. POST `/tracking-update` with step C JSON → fake `fulfillment_event_id`.

## Operational notes

- Each shipment sync **cancels all existing fulfillments** on the order, then recreates from the payload (destructive resync by design).
- Shopify **webhooks always return 200** even when monolith sync fails; monitor DSS logs (`error` level) and `dss.webhook.outcome=failed` MDC on monolith 5xx.
- Orders with more than 100 line items may truncate in `GetOrderForDss` (`lineItems(first: 100)`).

## Automated tests in this repo

```powershell
.\gradlew.bat test
```

Covers request validation, fulfillment order matching, HTTP status mapping, and order mapper FO id behavior.
