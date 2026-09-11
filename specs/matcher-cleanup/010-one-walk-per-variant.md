# Spec: one walk per variant in the shipment matcher

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: nothing. This spec owns the change that makes the plan carry the skips (change 4 below):
`specs/shipment-sync-by-tracking-number/010-skip-shipments-already-fulfilled.md` builds on it and `030` in that folder
widens the plan per shipment, so this spec lands first.
Repos: DSS only.


## Problem

The matcher answers the right thing and does so several times over:

- `determineShopifyMutations` runs `dryRunAllShipments` twice on the same order: once inside
  `calculateShopifyMutations` to plan, once in `logSkippedShipmentLines` only to log the skips, because the plan
  does not carry them.
- For every shipment line, `findOpenFulfillmentOrderLinesForVariant` in
  `domain/fulfillment/matchShipmentToFulfillmentOrders.kt` walks every fulfillment order and every line of the
  order, and then a second helper walks it again: `findBestFulfillmentOrderLineCandidate` when an open line carries
  the variant, `determineSkipReason` when none does. An order of `f` fulfillment orders with `l` lines each costs
  `2 × f × l` per shipment line, on top of the `FulfillmentQuantityLedger`'s own walk per dry run, and all of it
  twice (see above).
- `determineSkipReason` runs only when no open line carries the variant, so its `seenOnOpenFulfillmentOrder` is
  always `false` and it can only ever answer `NO_OPEN_FO`: its `ZERO_REMAINING` branch is unreachable (the
  reachable `ZERO_REMAINING` is decided in `matchShipmentLineItem`). `SkipReason.VARIANT_NOT_FOUND` is produced
  nowhere; its `variant_not_found` log label is dead.
- `ShipmentMatchResult.Ok.groups` is keyed by the whole generated `FulfillmentOrder` node, a data class whose
  `hashCode` and `equals` walk the nested line-item edges. The only thing read from the key is `id`.


## What changes

1. **One index per dry run.** `dryRunAllShipments` builds, beside the `FulfillmentQuantityLedger`, an
   `OpenFulfillmentLines` value (in `domain/fulfillment/`) in one walk of the order: for each open fulfillment
   order (status `OPEN` or `IN_PROGRESS`), each line whose variant id reads as a `Long`, grouped by that id and
   in Graphql order. A line whose variant id cannot be read is left out, as today. `matchShipmentToFulfillmentOrders`
   takes the index and the ledger and never walks the order itself; the public signature keeps `order` as the
   first parameter for callers that pass nothing else (the index and the ledger default to fresh ones), which is
   what the pure tests do.
2. **One decision per line, from that index.** With `lines = index.linesFor(variantId)`:
   - empty → `Skip(NO_OPEN_FO)`;
   - every line's snapshot `remainingQuantity` is `0` → `Skip(ZERO_REMAINING)`;
   - otherwise the candidate is the line with the highest ledger availability, first in Graphql order on a
     tie; no candidate with availability, or a quantity above the candidate's, is the `UserError` it is today.
   `determineSkipReason` and `SkipReason.VARIANT_NOT_FOUND` are deleted, and `logLabel()` loses the case.
3. **Groups keyed by id.** `ShipmentMatchResult.Ok.groups` becomes `Map<String, List<FulfillmentOrderLineItemInput>>`,
   keyed by the fulfillment order's gid and in first-seen order (a `LinkedHashMap`, as today). `calculateShopifyMutations`
   reads the key instead of `fulfillmentOrder.id`. Nothing else reads the groups.
4. **The plan carries the skips.** `calculateShopifyMutations` answers a `ShipmentPlan(mutations, skippedLines,
   unmatchedShipments)`, and `determineShopifyMutations` logs from the plan. `logSkippedShipmentLines` and the second
   dry run go. The tracking-number folder adds its shipment-level skip to this plan (`010`) and later widens it to
   one result per shipment (`030`).


## Behavioral contract

- **Invariant**: for every order and payload, the plan, the skipped lines, the user errors and their messages are
  the same as today. Every test in `MatchShipmentToFulfillmentOrdersTest`, `CalculateShopifyMutationsTest`,
  `DetermineShopifyMutationsTest` and `MonolithWebhookHandlersTest` passes unchanged, except where it names the
  deleted reason or reads a group key.
- **Skip versus error, unchanged**: the skip reasons look at the *snapshot* (`remainingQuantity`), the errors at
  the *ledger*. A line with `remainingQuantity = 1` whose unit an earlier shipment of the same payload consumed
  is not `ZERO_REMAINING` but the `exceeds remaining 0` user error, because two shipments claiming one unit is a
  payload problem, not a fulfilled line. This spec keeps that distinction; it is the one place the two sources
  of truth legitimately differ.
- **Cost**: one walk of the order per dry run (two when the index sits beside the ledger, see open question 1),
  then one map lookup per shipment line.
- **Log lines**: identical, minus the never-emitted `reason=variant_not_found`.


## Edge cases

- The same variant on two open fulfillment orders: the higher ledger availability wins, first in Graphql order
  on a tie (pinned by `prefers fulfillment order with highest available remainingQuantity`).
- The same variant on two lines of one fulfillment order: the line with more remaining (pinned by
  `the same variant on two lines of one open fulfillment order takes the line with more remaining`).
- An unreadable variant id on an open line: absent from the index, so it neither matches nor changes another
  variant's reason (pinned by `an unreadable variant id on an open line does not change the skip reason of
  another variant`).
- A closed fulfillment order with the variant: not in the index; the line is `NO_OPEN_FO`.
- A payload with no shipments: an empty plan, no skips, no log line.


## Reuse inventory

- `FulfillmentQuantityLedger.init` walks the order the same way; the index and the ledger can be built in the
  same loop, or the index can be what the ledger is built from.
- `isOpenForFulfillment()` in `FulfillmentQuantityLedger.kt` is the one definition of "open".
- `ShipmentMatchResult`, `DryRunResult`, `SkippedShipmentLine`: kept, with the key type of `groups` changed.
- `logSkippedShipmentLines` in `determineShopifyMutations.kt` has the log format; it moves to read the plan.
- Fixtures: `openFulfillmentOrder(...)`, `orderWithFulfillmentOrders(...)` in `testutil/fixture/FulfillmentOrderFixtures.kt`.


## Test plan

- **Pure** (`MatchShipmentToFulfillmentOrdersTest`): the suite passes as is, minus any case naming
  `VARIANT_NOT_FOUND` (there should be none; the reason was unreachable). One new case pins the key type: the
  cross-FO shipment's groups are keyed by `gid://shopify/FulfillmentOrder/301` and `302`, in that order.
- **Pure** (a new `OpenFulfillmentLinesTest` beside the new file): open and closed orders, an unreadable id,
  Graphql order preserved, `linesFor` of an unknown variant is empty.
- **Fake-backed** (`DetermineShopifyMutationsTest`): `orderForDss` is called once (already pinned), and a new
  case under `capturingLogs` shows the skip lines are logged from the plan, one per skipped line and one
  `all_lines_unmatched` per shipment without groups, with the same `reason=` labels as today.
- **Fake-backed** (`CalculateShopifyMutationsTest`): the plan carries the skipped lines and the unmatched
  shipments that the old second dry run used to recompute.


## Open questions for the human developer

1. Should the index replace the ledger's own walk (build the ledger from the index) or sit beside it? Replacing
   is one walk; beside is two, and simpler to review.
2. `ZERO_REMAINING` from the snapshot versus the ledger, as kept here, or should a line the payload itself
   exhausted also read as a skip? The current tests pin the error; changing it changes what the monolith is
   told (a `400` today).
