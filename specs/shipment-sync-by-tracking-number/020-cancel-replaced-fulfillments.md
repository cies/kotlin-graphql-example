# Spec: cancel the fulfillments the monolith replaced

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `010`.
Repos: monolith first, then the DSS in the same session (`../CLAUDE.md`, "Cross-repo couplings").


## Problem

The supplier portal's shipment split marks the old shipments `replaced_at`, inserts the new ones and
enqueues the sync. The DSS receives only the new shipments, finds their fulfillment-order lines at
`remainingQuantity` 0 because the old fulfillments still hold the units, skips every line, and answers
`200` with nothing created. Shopify keeps the old tracking numbers; the next `/tracking-update` for a new
number answers `404`. Nothing errors, and the supplier believes Shopify was updated. Details and the
sequence diagram are in `000-analysis-cancel-and-recreate.md`.

Cancelling every fulfillment (the July design) is not the answer either: the monolith only ever sends
unsynced shipments, so cancel-all destroyed earlier, still-valid fulfillments whenever a supplier added a
later shipment. The DSS has to be told which fulfillments to cancel, and the monolith knows: the shipments
with `replaced_at` set.


## What changes

### Contract (monolith serves it, DSS checks in the copy)

`SyncShipmentsWithFulfillmentsRequest` gains:

```json
"replaced_tracking_numbers": { "type": "array", "items": { "type": "string" } }
```

Required in the spec and always sent (an empty list when nothing was replaced). `AppJson` has
`ignoreUnknownKeys = true`, so a DSS that predates this field keeps working while the monolith deploys first.
The example in `SyncShipmentsWithFulfillmentsRequest.example` shows one replaced number.

### Monolith

- `db/sql/job/syncShipmentsWithFulfillmentsRead.kt`: `selectSyncShipmentsWithFulfillmentsPayload` also selects
  the tracking numbers of shipments of the same `shopify_order_id` with `replaced_at is not null`, and puts
  them in the request. All of them, every run: the DSS treats a cancel of an already-cancelled fulfillment as
  done, so repeating the list is free and makes a retry after a failed cancel converge.
- The job's "nothing left to sync" no-op must not swallow a pending cancel: a run with no unsynced shipments
  but with replaced ones that were never mirrored still has to POST. The simplest rule that gives this is to
  stamp replaced shipments too, in a `replacement_synced_at` (or reuse `synced_at`, which a replaced shipment
  already has set: then the trigger is "replaced after the last sync"). The monolith spec decides; the DSS side
  only needs the list.
- The split page copy says the split "will cancel the current shipment(s) and create new ones", which becomes
  true. Its claim about shipping-notification e-mails stays false while `notifyCustomer` is `false`; see the
  open questions.

### DSS

- The domain validators accept the field: strings, trimmed, non-blank, no duplicates; a replaced number that
  also appears in `shipments` is allowed (a supplier re-used a tracking number after a split).
- `calculateShopifyMutations` plans, in this order:
  1. one `FulfillmentCancel` per live fulfillment (status not `CANCELLED`) whose `trackingInfo` contains a
     replaced tracking number, in payload order of the numbers;
  2. the creates of `010`, computed against a ledger that has been **credited** with the quantities the
     cancels free up.
- Crediting the ledger needs the cancelled fulfillments' line quantities per fulfillment-order line.
  `GetOrderForDss.graphql` therefore adds, under `fulfillments`,
  `fulfillmentLineItems(first: 100) { nodes { quantity lineItem { id variant { legacyResourceId } } } }`.
  A cancel returns each of those quantities to the open fulfillment-order line of the same variant. (The
  alternative, cancel first and reload the order before planning the creates, needs no query change but gives
  up planning everything before the first mutation; rejected for that reason, see open question 1.)
- `effectShopifyMutations` runs cancels before creates, stopping at the first failure as today, but answers
  what it did before stopping: the cancelled ids and the created ids so far, beside the failure (`030` defines
  the shape). The summary log line's `canceled=` count becomes meaningful again.
- `ShopifyError.UserError` from a cancel that is not "already cancelled" fails the whole sync as a `400`,
  exactly as a refused create does: the monolith drops a 4xx for good, and a human looks at the log. A
  transport or Graphql failure is a `502` and the monolith retries; the replaced list is re-sent in full, so
  the retry converges.


## Behavioral contract

- **Precondition**: `010` is in place, so a re-sent shipment cannot be created twice.
- **Postcondition**: after a successful run, no live fulfillment of the order carries a replaced tracking
  number, and every shipment in the payload has a live fulfillment.
- **Invariant**: the DSS cancels nothing it was not named. A fulfillment a merchant created by hand in
  Shopify, or one belonging to a shipment the monolith still considers live, is never touched.
- **Idempotence**: a second identical request plans no cancel (the fulfillments are `CANCELLED`) and no
  create (`010`), and answers `200`.


## Edge cases

- **Replaced number not on any fulfillment**: nothing to cancel; logged at `info`. The shipment may never
  have been mirrored (it was replaced before its first sync).
- **Replaced number only on a cancelled fulfillment**: nothing to cancel, no log line.
- **Replaced number also in `shipments`** (re-used tracking number): the cancel runs first, so the create
  sees a cancelled fulfillment and proceeds. `010`'s skip ignores cancelled fulfillments for exactly this case.
- **A cancel frees quantity on a fulfillment order the new shipments do not use**: the credit is harmless;
  the quantity stays unfulfilled in Shopify, which is the truth.
- **Shopify refuses the cancel** (a fulfillment already delivered, for example): `400`, nothing created, the
  order is left as it was. Open question 2 asks whether that is what the split page should promise.
- **Partial failure** (cancel done, create failed): the retry re-sends the same payload with the same
  `replaced_tracking_numbers`; the cancel finds the fulfillment already `CANCELLED` and plans nothing for it,
  the creates that landed are skipped as already fulfilled (`010`), the failed create is attempted again. No
  cancel is repeated and no duplicate can arise. The effect step answers the cancelled ids and the created ids
  up to the failure together with the failure (`030`, "A run that fails part-way"), so the monolith's log of the
  failed run still says which fulfillments were cancelled.
- **The replaced fulfillment carried tracking events**: they are lost with the cancel. Same as the July design;
  noted in the docs (`040`).


## Reuse inventory

- `ShopifyMutation.FulfillmentCancel`, the cancel branch of `effectShopifyMutations`,
  `ShopifyGraphqlService.cancelFulfillment` and `FulfillmentCancelMutation.graphql`: all present, all
  tested, currently without a producer. This spec is the producer.
- `HttpShopifyGraphqlService.cancelFulfillment` already treats an "already cancelled" user error as success.
- `FulfillmentQuantityLedger` gains a `credit(lineItemGid, quantity)`; the `init` block that reads
  `remainingQuantity` is the model for it.
- The fulfillment-by-tracking-number lookup from `010`.
- `RequestValidation.kt`'s `validateDuplicateTrackingNumbers` shows the validator style for the new list.
- Monolith: `db/sql/portal/supplier/shipmentSplitWrite.kt` sets `replaced_at`; `shipmentSplitRead.kt` already
  filters on it, so the column and its meaning exist.


## Test plan

- **Pure**: ledger credit; the cancel plan for live, cancelled and absent tracking numbers; the create plan
  after a credit (the split scenario: 2 × X fulfilled under A, payload `shipments=[B(1×X), C(1×X)]`,
  `replaced=[A]` → cancel A, create B and C).
- **Fake-backed** (`SyncShopifyShipmentsToFulfillmentsTest`): mutation order is cancels then creates; a
  refused cancel stops before any create; the summary line counts `canceled=1`.
- **Request → response** (`MonolithWebhookHandlersTest`): the split scenario through `withDssApp`; a payload
  with `replaced_tracking_numbers` missing still decodes (older monolith) and behaves as an empty list; a
  blank replaced number is a `400`.
- **Wire**: `orderForDss` deserializes `fulfillmentLineItems`.
- **Monolith** (`*DbTest` for the read query, the job's `*Test`): replaced shipments of the order appear in the
  payload; a run with only replaced shipments still POSTs.


## Open questions for the human developer

1. Credit the ledger from `fulfillmentLineItems` (plan everything, then mutate) or cancel, reload, then plan
   the creates (simpler query, one more Graphql round trip, a mutation before the plan is complete)? This spec
   picks the first; both are workable.
2. When Shopify refuses a cancel, should the DSS still create the new shipments' fulfillments where quantity
   allows, or fail the whole request as written here? Failing keeps the order in one of two known states.
3. Should `notify_customer` become a request field, so the split page's e-mail claim can be made true, or
   should the page stop promising e-mails? Out of scope for the DSS either way; it needs a decision on the
   monolith side.
