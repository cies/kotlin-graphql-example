# Spec: report per-shipment outcomes in the sync response

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `010`; the cancelled ids come with `020`.
Repos: monolith first (it serves the spec and consumes the response), then the DSS.


## Problem

`SyncShipmentsWithFulfillmentsResponse` is a bare `new_fulfillment_ids` list. After `010` a shipment can be
skipped because it already has a fulfillment, after `020` fulfillments get cancelled, and neither shows in
the response: the monolith cannot tell "created" from "already there" from "skipped because no line matched",
and it cannot learn which fulfillment id belongs to which tracking number. Today it does not try to: the job
stamps `synced_at` and drops the ids. Once it wants to store the fulfillment id per shipment (for the
tracking-update flow, or for the admin portal), it needs the mapping. And when a run fails part-way, the monolith
learns nothing about what landed (see "A run that fails part-way").


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

| Outcome | Meaning | Appears in |
|---------|---------|------------|
| `created` | This run created the fulfillment; `fulfillment_id` is its id. | Any response |
| `already_fulfilled` | A live fulfillment with this tracking number existed (`010`); `fulfillment_id` is that one. | Any response |
| `unmatched` | No line matched an open fulfillment-order line; nothing was created (the `all_lines_unmatched` warning). | Any response |
| `failed` | This run tried to create the fulfillment and Shopify or the wire refused; the run stopped here. | A non-2xx response only |
| `not_attempted` | Nothing was sent for it: it comes later in the payload than the failed create, or the run stopped before it was classified. | A non-2xx response only |

`fulfillment_id` is empty for `unmatched`, `failed` and `not_attempted` (open question 2 decides between `null`
and an absent key). Two entries may carry the same `fulfillment_id`: a fulfillment can hold several tracking
numbers, and `010` skips every shipment whose number it holds.

`canceled_fulfillment_ids` lists the fulfillments this run cancelled (`020`); it is empty until `020` lands.

`new_fulfillment_ids` is dropped rather than kept beside the list: two fields describing the same run are
two chances to disagree. The monolith's `DssWebhookService` reads the new shape in the same change.

### DSS

- `syncShopifyShipmentsToFulfillments` answers a `ShipmentSyncReport` (in `domain/`, beside
  `WebhookRegistrationReport` and `ShopInstallReport`, per the naming rule for a summary of many results) instead of
  `List<ShopifyFulfillmentId>`, on success and on failure. The per-shipment results of `010` already carry what is
  needed.
- The handler maps a successful report onto the response DTO. A failed run needs a new `DssError` member that
  carries the report beside the failure (for example `DssError.ShipmentSyncFailed(report, cause)`): its status is
  the one `cause.toDssError()` answers today, and its body is the report plus `error` and `trace_id`. Every other
  member keeps the plain error body.
- The summary log line is derived from the same report, so log and response cannot diverge.

### Monolith

- `lib/dss/DssWebhookService.kt` decodes the report from a `200`, and from a `400` or `502` of the sync when the
  body carries one (a `401`, or a `400` from request validation, does not).
- `job/SyncShipmentsWithFulfillments.kt` stamps `synced_at` from the report; see "A run that fails part-way",
  point 3. Storing `fulfillment_id` on the shipment is optional (a column and a migration; open question 1).


## Behavioral contract

- **Postcondition**: every tracking number in the request appears exactly once in `shipments`.
- **Invariant**: `created` entries carry ids that this run created and nothing else; `canceled_fulfillment_ids`
  lists exactly the fulfillments this run cancelled.
- **Failure**: the status is unchanged, a `400` when Shopify refused what was sent and a `502` when Shopify or
  the wire failed, so the monolith's retry-or-drop rule is untouched. The body changes: a failed run answers the
  same per-shipment report as a successful one, so the monolith learns what landed before the failure.


## A run that fails part-way

Today the effect step returns on the first failed create and discards the ids it already collected, and the
handler answers a body that names none of them. A payload of two shipments whose second create fails leaves
shipment one fulfilled in Shopify and unknown to the monolith: on a `502` the monolith re-sends both (safe
after `010`, but the id is still never reported), on a `400` it drops both for good and shipment one stays
unstamped forever. Three statements close this:

1. **The effect step answers both halves.** `effectShopifyMutations` returns one value carrying the fulfillment
   ids created (and, after `020`, cancelled) so far and, when it stopped early, the failure that stopped it. It never
   answers a bare `Failure` that drops the list. The mutations after the failed one are not attempted, as today.
2. **A failed run answers the report.** The body of a `400` or `502` from the sync has the shape of a `200` body plus
   a top-level `error` carrying the message today's error body carries. The shipments that landed are `created`
   with their ids, the shipments the plan skipped keep `already_fulfilled` or `unmatched`, the shipment whose create
   failed is `failed`, and every later one is `not_attempted`. The body holds **at most one** `failed` entry: one when
   a create failed, none when the run stopped before its first create (the order could not be loaded, the plan was
   refused, a cancel failed, or the plan after `020`'s reload failed). In that case every shipment the run had not
   classified is `not_attempted`, and `canceled_fulfillment_ids` says what the cancels did.
3. **The monolith stamps what landed.** On a failed run the monolith's sync job stamps the `created` and
   `already_fulfilled` shipments before it retries or gives up, so a `400` after one success no longer leaves that
   shipment unstamped. This is the reason the report has to be on the error path at all. It changes the job's shape:
   today a transient failure is thrown from inside `db.transaction` (`retryUnlessRefused` throws for
   `IntegrationError.Transport`), which rolls back everything in the transaction, stamps included. The stamps must be
   committed before the retry is signalled, for example by committing and then throwing outside the transaction.
   The monolith spec chooses the mechanism, and keeps what `acquireShopifyOrderSyncLock` protects.

The `ApiError` fields the monolith reads today (`error`, `trace_id`) keep their names and position, so a
monolith that only reads them keeps working during the deploy gap.


## Edge cases

- **Empty payload**: refused by the validators today; unchanged.
- **Two entries with the same `fulfillment_id`**: possible when one fulfillment holds both tracking numbers (see
  "Contract"); the monolith must not treat `fulfillment_id` as unique within a response.
- **A shipment partially matched** (some lines skipped, some created): `created`, with the line-level skips
  in the log as today. A per-line breakdown in the response is not worth its surface.
- **The failure is a network error on the create itself**: Shopify may have created the fulfillment without
  the DSS learning its id. The report says `failed`; the retry (`010`) then finds the tracking number on a live
  fulfillment and reports it `already_fulfilled` with the id. No id is ever guessed.
- **The failure happens before any create**: no `failed` entry; the shipments the run had not classified are
  `not_attempted`, which is today's behaviour with a body that says so.
- **A retry after a partial failure**: the payload is re-sent in full; the shipments that landed come back as
  `already_fulfilled`, the failed one is attempted again, no cancel is repeated (`020`).


## Reuse inventory

- `WebhookRegistrationReport` and `ShopInstallReport` in `domain/` show the report shape and naming.
- `MonolithWebhookHandlers.handleSyncShipments` is the one place the response is built; `respondError` and
  `DssError.toHttpStatus` in `lib/ktor/DssError.kt` take the new member.
- The monolith's `DssWebhookService.syncShipmentsWithFulfillments`, the job in `job/SyncShipmentsWithFulfillments.kt`
  and `job/retryUnlessRefused.kt` are the only consumers and the retry rule.


## Test plan

- **Fake-backed**: the report for a mixed payload (created, already fulfilled, unmatched) after `010`/`020`.
- **Request → response**: the JSON shape, key by key, the way `HttpMonolithServiceTest` pins outbound bodies,
  including the empty `fulfillment_id` as open question 2 decides it.
- **Fake-backed** (`EffectShopifyMutationsTest`): the second of two creates fails; the result carries the first
  id and the failure. `SyncShopifyShipmentsToFulfillmentsTest`: the same, through the workflow.
- **Request → response** (`MonolithWebhookHandlersTest`): the second create fails with a Shopify `5xx`; the
  answer is a `502` whose body has one `created` entry with its id, one `failed`, and `error` set. The same
  with a Shopify user error is a `400` with the same body shape. The order load fails: a `502` whose body has no
  `failed` entry and every shipment `not_attempted`.
- **Monolith**: `DssWebhookService` decodes an example of each outcome, including a failed run; the job commits the
  stamps of the `created` and `already_fulfilled` shipments of a failed run, leaves the rest unstamped, and still
  retries after a `502`.


## Open questions for the human developer

1. Store `fulfillment_id` on the monolith's `shipments` row now, or wait for a consumer?
2. Write an empty `fulfillment_id` as `null` or leave the key out? `AppJson` has `explicitNulls = false`, so a
   `null` has to be asked for (a required, nullable property in the spec), and the monolith's parser must agree.
