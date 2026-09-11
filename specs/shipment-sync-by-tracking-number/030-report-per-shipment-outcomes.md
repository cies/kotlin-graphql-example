# Spec: report per-shipment outcomes in the sync response

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `010`; benefits from `020`.
Repos: monolith first (it serves the spec and consumes the response), then the DSS.


## Problem

`SyncShipmentsWithFulfillmentsResponse` is a bare `new_fulfillment_ids` list. After `010` a shipment can be
skipped because it already has a fulfillment, after `020` fulfillments get cancelled, and neither shows in
the response: the monolith cannot tell "created" from "already there" from "skipped because no line matched",
and it cannot learn which fulfillment id belongs to which tracking number. Today it does not try to: the job
stamps `synced_at` and drops the ids. Once it wants to store the fulfillment id per shipment (for the
tracking-update flow, or for the admin portal), it needs the mapping.


## What changes

### Contract

`SyncShipmentsWithFulfillmentsResponse` becomes:

```json
{
  "shipments": [
    { "tracking_number": "1Z…", "outcome": "created",           "fulfillment_id": 4001 },
    { "tracking_number": "1Z…", "outcome": "already_fulfilled", "fulfillment_id": 3990 },
    { "tracking_number": "1Z…", "outcome": "unmatched",         "fulfillment_id": null }
  ],
  "canceled_fulfillment_ids": [3985]
}
```

One entry per shipment in the payload, in payload order. `outcome` is a closed set:

| Outcome | Meaning |
|---------|---------|
| `created` | This run created the fulfillment; `fulfillment_id` is its id. |
| `already_fulfilled` | A live fulfillment with this tracking number existed (`010`); `fulfillment_id` is that one. |
| `unmatched` | No line matched an open fulfillment-order line; nothing was created (the `all_lines_unmatched` warning). |
| `failed` | Only on a non-2xx: the create was refused or the wire failed, and the run stopped here. |
| `not_attempted` | Only on a non-2xx: later in the payload than the failure, nothing was sent for it. |

`new_fulfillment_ids` is dropped rather than kept beside the list: two fields describing the same run are
two chances to disagree. The monolith's `DssWebhookService` reads the new shape in the same change.

### DSS

- `syncShopifyShipmentsToFulfillments` answers a report (`ShipmentSyncReport` in `domain/`, per the naming
  rule for a summary of many results) instead of `List<ShopifyFulfillmentId>`; the handler maps it onto the
  response DTO. The per-shipment results of `010` already carry what is needed.
- The summary log line is derived from the same report, so log and response cannot diverge.

### Monolith

- `lib/dss/DssWebhookService.kt` decodes the new response; the job stamps `synced_at` as today and, if the
  human developer wants it, stores `fulfillment_id` on the shipment (a column and a migration; not required
  by this spec).


## Behavioral contract

- **Postcondition**: every tracking number in the request appears exactly once in `shipments`.
- **Invariant**: `created` entries carry ids that this run created and nothing else; `canceled_fulfillment_ids`
  lists exactly the fulfillments this run cancelled.
- **Failure**: the status is unchanged, a `400` when Shopify refused what was sent and a `502` when Shopify or
  the wire failed, so the monolith's retry-or-drop rule is untouched. The body changes: a failed run answers the
  same per-shipment report as a successful one, so the monolith learns what landed before the failure. See
  "A run that fails part-way" below.


## A run that fails part-way

Today the effect step returns on the first failed create and discards the ids it already collected, and the
handler answers a body that names none of them. A payload of two shipments whose second create fails leaves
shipment one fulfilled in Shopify and unknown to the monolith: on a `502` the monolith re-sends both (safe
after `010`, but the id is still never reported), on a `400` it drops both for good and shipment one stays
unstamped forever. Three statements close this:

1. **The effect step answers both halves.** `effectShopifyMutations` returns one value carrying the fulfillment
   ids created so far and, when it stopped early, the failure that stopped it. It never answers a bare `Failure`
   that drops the list. The mutations after the failed one are not attempted, as today.
2. **A failed run answers the report.** The response body on a `400` or `502` is the same shape as on a `200`,
   with the shipments that landed as `created` (with their ids), the shipment whose create failed as `failed`,
   and the shipments after it as `not_attempted`, plus a top-level `error` carrying the message the error body
   carries today. `canceled_fulfillment_ids` lists what was cancelled before the failure. So `outcome` gains
   two values:

   | Outcome | Meaning |
   |---------|---------|
   | `failed` | This run tried to create the fulfillment and Shopify or the wire refused; the run stopped here. |
   | `not_attempted` | Later in the payload than the failure; nothing was sent for it. |

   Both appear only on a non-2xx response, and a non-2xx response carries exactly one `failed` entry.
3. **The monolith stamps what landed.** On a failed run the monolith's sync job stamps the `created` shipments
   as synced before it retries or gives up, so a `400` after one success no longer leaves that shipment
   unstamped. This is the reason the report has to be on the error path at all. A shipment reported
   `already_fulfilled` on a failed run is stamped too, for the same reason.

The `ApiError` fields the monolith reads today (`error`, `trace_id`) keep their names and position, so a
monolith that only reads them keeps working during the deploy gap.


## Edge cases

- **Empty payload**: refused by the validators today; unchanged.
- **Two `already_fulfilled` entries pointing at the same fulfillment**: impossible after the payload's
  duplicate-tracking-number check.
- **A shipment partially matched** (some lines skipped, some created): `created`, with the line-level skips
  in the log as today. A per-line breakdown in the response is not worth its surface.
- **The failure is a network error on the create itself**: Shopify may have created the fulfillment without
  the DSS learning its id. The report says `failed`; the retry (`010`) then finds the tracking number on a live
  fulfillment and reports it `already_fulfilled` with the id. No id is ever guessed.
- **The failure happens before any create** (the order could not be loaded, the plan was refused): every
  shipment is `not_attempted`, which is today's behaviour with a body that says so.
- **A retry after a partial failure**: the payload is re-sent in full; the shipments that landed come back as
  `already_fulfilled`, the failed one is attempted again, no cancel is repeated (`020`).


## Reuse inventory

- `WebhookRegistrationReport` and `ShopInstallReport` in `domain/` show the report shape and naming.
- `MonolithWebhookHandlers.handleSyncShipments` is the one place the response is built.
- The monolith's `DssWebhookService.syncShipmentsWithFulfillments` and the job in
  `job/SyncShipmentsWithFulfillments.kt` are the only consumers.


## Test plan

- **Fake-backed**: the report for a mixed payload (created, already fulfilled, unmatched) after `010`/`020`.
- **Request → response**: the JSON shape, key by key, the way `HttpMonolithServiceTest` pins outbound bodies;
  `fulfillment_id` written as `null` for `unmatched` (`AppJson` has `explicitNulls = false`, so this needs a
  deliberate choice: either make the field required-nullable in the spec or omit it; the monolith's parser
  must agree).
- **Fake-backed** (`EffectShopifyMutationsTest`): the second of two creates fails; the result carries the first
  id and the failure. `SyncShopifyShipmentsToFulfillmentsTest`: the same, through the workflow.
- **Request → response** (`MonolithWebhookHandlersTest`): the second create fails with a Shopify `5xx`; the
  answer is a `502` whose body has one `created` entry with its id, one `failed`, and `error` set. The same
  with a Shopify user error is a `400` with the same body shape.
- **Monolith**: `DssWebhookService` decodes an example of each outcome, including a failed run; the job stamps
  the `created` and `already_fulfilled` shipments of a failed run and leaves the rest unstamped.


## Open questions for the human developer

1. Store `fulfillment_id` on the monolith's `shipments` row now, or wait for a consumer?
