# Fulfillment verification (DSS)

Checklist for verifying **supplier shipment → Shopify fulfillment** via the monolith → DSS path. DSS does not call the Supplier API directly; the monolith bridges supplier events to DSS webhooks defined in [`docs/openapi/dss-api.yaml`](openapi/dss-api.yaml).

## Prerequisites

- Shopify app scopes include `write_merchant_managed_fulfillment_orders` (and read fulfillment orders).
- Monolith and DSS share `DSS_API_KEY`: the monolith sends it as `Authorization: Bearer <DSS_API_KEY>` on `POST /sync-shipments-with-fulfillments`, `POST /tracking-update`, and `PUT /stores/api-key`; the matching auth guard is `installMonolithWebhookAuth`.
- Shopify Admin token resolvable for the shop — seed `DSS_SHOP_ACCESS_TOKENS`, complete OAuth, or have the monolith persist one via `PUT /stores/api-key` (a miss falls back to `MonolithService.getStore`).
- `MONOLITH_BASE_URL` set on DSS for order/product webhooks (Shopify → monolith).

## Path reference

| Monolith intent | DSS URL | Request body |
|-----------------|---------|--------------|
| Supplier created/reorganized shipment | `POST /sync-shipments-with-fulfillments` | `SyncShipmentsWithFulfillmentsRequest` |
| Carrier tracking status (e.g. AfterShip) | `POST /tracking-update` | `TrackingUpdateRequest` |

See also [`docs/openapi/dss-api.yaml`](openapi/dss-api.yaml) and [`specs/fulfillment-shipment-fo-mapping.md`](../specs/fulfillment-shipment-fo-mapping.md).

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

3. Expect **HTTP 200** and a JSON body with `new_fulfillment_ids` (array of Shopify fulfillment legacy IDs created during this sync):

```json
{
  "new_fulfillment_ids": [5001]
}
```

- One entry per shipment that had at least one matchable line item (one `fulfillmentCreate` per such shipment).
- Empty array when every line in every shipment was skipped (see partial match below) — still **200**.
4. In Shopify Admin: order shows fulfillment with tracking; matched line items marked fulfilled.

### Sync phases (validate-before-cancel)

DSS never cancels existing fulfillments until the full payload passes validation against the current order:

```
load order → dry-run match ALL shipments → if ANY hard error → 400, NO cancels
           → cancel all existing fulfillments
           → reload order
           → create fulfillments one-by-one (reload after each success)
```

This means a bad quantity in the payload leaves existing Shopify fulfillments untouched.

### Partial match (unmatched variants)

When a shipment line's `product_variant_id` does not appear on any open fulfillment order line, DSS **skips that line** and continues — it does not fail the whole sync.

| Situation | HTTP | Shopify effect |
|-----------|------|----------------|
| Line matches open FO with sufficient qty | 200 | Included in `fulfillmentCreate` |
| Variant not on any open FO | 200 | Line skipped; matched lines still fulfilled |
| All lines in a shipment unmatched | 200 | No create for that shipment; `new_fulfillment_ids` omits it |
| Qty exceeds remaining (single line or cross-shipment total) | **400** (before cancel) | Existing fulfillments untouched |
| Order not found | **404** | No mutations |

**Partial-match manual check:**

1. Send a shipment with one known-good variant and one bogus `product_variant_id` (e.g. `999`).
2. Expect **HTTP 200** with `new_fulfillment_ids` containing one ID.
3. In Shopify Admin: the good variant is fulfilled; the bogus variant stays unfulfilled.
4. In DSS logs at `warn`: `sync-shipments skipped line … variant=999 reason=no_open_fo`.

**Hard-failure manual check (validate-before-cancel):**

1. Note existing fulfillments on a test order.
2. POST a payload where a line quantity exceeds remaining FO quantity.
3. Expect **400**; confirm existing fulfillments are still present in Shopify Admin.

**Failure checks (request validation and sync):**

- Malformed body, missing fields, non-positive `shopify_order_id` → **400**.
- Quantity greater than remaining on FO (including cross-shipment over-allocation) → **400** before any cancel.
- Order not found in Shopify → **404**.
- No resolvable Shopify Admin token for the shop → **401**.
- Cancel or create GraphQL failure (non–already-canceled) → **4xx/5xx**; partial creates may exist until monolith retries the full payload.

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

## Operational notes

- Each successful sync **cancels all existing fulfillments** on the order, then recreates from the payload (destructive resync by design). Validation runs first so hard errors do not leave the order with zero fulfillments.
- Re-sending the same payload is idempotent: cancel whatever exists → recreate identical fulfillments. Tracking events on canceled fulfillments are lost (known trade-off).
- DSS logs a summary at `info` after each sync, e.g. `sync-shipments orderId=1001 shop=acme canceled=2 created=1 skippedLines=1 skippedShipments=0 fulfillmentIds=[5001]`.
- Per skipped line at `warn`: `sync-shipments skipped line … tracking=… variant=… reason=no_open_fo qty=…`.
- Shopify **webhooks always return 200** even when monolith sync fails; monitor DSS logs (`error` level) and `dss.webhook.outcome=failed` MDC on monolith 5xx.
- Orders with more than 100 line items may truncate in `GetOrderForDss` (`lineItems(first: 100)`).

## Extended manual checklist

1. **Cross-FO shipment** — one shipment spanning two fulfillment orders → one Shopify fulfillment, correct tracking, both FOs reflected.
2. **Bad quantity** — payload with qty > remaining → **400**, existing fulfillments untouched.
3. **Partial match** — one unmatched variant in payload → **200**, partial fulfillment created, skipped line in logs.
4. **Idempotent retry** — re-send same payload → cancel + recreate, same outcome.
5. **Reorganized splits** — supplier changes shipment groupings → new fulfillments match new payload.

## Automated tests in this repo

```powershell
.\gradlew.bat test
```

Covers request validation, fulfillment order matching, HTTP status mapping, and order mapper FO id behavior.
