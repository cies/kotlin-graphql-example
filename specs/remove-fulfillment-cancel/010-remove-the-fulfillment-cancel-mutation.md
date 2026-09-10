# Spec: remove the fulfillment-cancel mutation

Status: draft
Author: cies (with Claude)
Date: 2026-09-10


## Problem

`ShopifyMutation` in `src/dropnext/dss/workflow/ShopifyMutation.kt` has two variants, `FulfillmentCancel` and
`FulfillmentCreate`. Only the second is ever constructed in production code. The first survives as a declaration, an
executing branch, a log counter that is always zero, and a handful of tests, with nothing that produces it.

The commit of 31 August 2026 ("Refactor fulfillment order matching … Remove unnecessary fulfillment cancellation
logic") changed the shipments sync from "cancel every existing fulfillment, then recreate from the payload" to "leave
existing fulfillments alone and create against the live remaining quantities". It removed the producer but left the
rest of the cancel machinery, and left two documents describing the old design as current.


## The only planner emits creates

`calculateShopifyMutations` is the one function that builds a plan. Its whole body is a single `mapIndexedNotNull`
over the matched shipments, and the lambda ends in one constructor:

```kotlin
// src/dropnext/dss/workflow/calculateShopifyMutations.kt
is DryRunResult.Ok -> Success(
  match.perShipment.mapIndexedNotNull { index, shipmentMatch ->
    if (shipmentMatch.groups.isEmpty()) return@mapIndexedNotNull null
    val shipment = shipments[index]
    val lineItems = /* one FulfillmentLine per matched line */
    ShopifyMutation.FulfillmentCreate(
      lineItems = lineItems,
      trackingNumber = shipment.trackingNumber,
      carrier = shipment.carrier,
      trackingUrl = shipment.trackingUrl,
      notifyCustomer = false,
    )
  },
)
```

There is no other branch. `determineShopifyMutations` loads the order and delegates to this function;
`syncShopifyShipmentsToFulfillments` composes the two. The list that reaches `effectShopifyMutations` can therefore
contain nothing but `FulfillmentCreate`.

This is the hunk that removed the producer:

```diff
     is DryRunResult.Ok -> {
       val mutations = mutableListOf<ShopifyMutation>()
-      order.fulfillments
-        .map { it.id }
-        .filter { it.isNotBlank() }
-        .forEach { fulfillmentId ->
-          mutations += ShopifyMutation.FulfillmentCancel(fulfillmentId = fulfillmentId)
-        }
       match.perShipment.forEachIndexed { index, shipmentMatch ->
```


## What is left: consumers without a producer

A grep for `FulfillmentCancel` and `cancelFulfillment` outside the generated Graphql code finds only these:

| Where | What | Why it still compiles |
|---|---|---|
| `workflow/ShopifyMutation.kt` | The variant declaration. | It is a declaration. |
| `workflow/effectShopifyMutations.kt` | The `when` branch that calls `cancelFulfillment`. | Reachable only with a hand-built list, which only its own test provides. |
| `workflow/syncShopifyShipmentsToFulfillments.kt` | `mutations.count { it is FulfillmentCancel }` in the summary log line. | Always zero. |
| `lib/shopify/graphql/ShopifyGraphqlService.kt` and `HttpShopifyGraphqlService.kt` | `cancelFulfillment(gid)` and its implementation over `FulfillmentCancelMutation`. | Called from the branch above only. |
| `src/resources/FulfillmentCancelMutation.graphql` | The operation the implementation runs. | Compiled by the Graphql plugin regardless of use. |
| `test/…/testutil/fake/FakeShopifyGraphqlService.kt` | `cancelFulfillmentResult`, `cancelFulfillmentCalls` and the override. | The fake mirrors the interface. |
| `test/…/lib/shopify/graphql/HttpShopifyGraphqlServiceTest.kt` | The wire-level test for `cancelFulfillment`. | `TestSuiteArchitectureTest` requires one per service method. |
| `CalculateShopifyMutationsTest`, `DetermineShopifyMutationsTest` | Five `assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })`. | Guard against the old behaviour returning. |
| `EffectShopifyMutationsTest` | Two tests that feed a `FulfillmentCancel` to the effect step. | Exercise the branch directly. |

The compiler cannot flag any of this: the variant is used in a `when` and in tests. Only the absence of a
`FulfillmentCancel(` call under `src/` shows it.


## What changes

1. **The mutation type.** Drop `FulfillmentCancel`. With one member left, the sealed interface no longer earns its
   name: replace it with a single data class describing what the planner produces, `PlannedFulfillment` (the same
   fields as today's `FulfillmentCreate`). `calculateShopifyMutations` answers `ShopifyResult<List<PlannedFulfillment>>`.
2. **The effect step.** `effectShopifyMutations` loses its `when` and becomes a loop that creates each planned
   fulfillment in order, stopping at the first failure, exactly as the create branch does today. Its name stays, or
   becomes `createPlannedFulfillments` if the reviewer prefers the file to say what it does; either way the `test/`
   mirror follows.
3. **The summary log line.** Drop `canceled=` from the `sync-shipments` line in `syncShopifyShipmentsToFulfillments`.
   Logflare searches on the other keys are unaffected.
4. **The Shopify service surface.** Remove `cancelFulfillment` from `ShopifyGraphqlService`, its implementation from
   `HttpShopifyGraphqlService`, the operation file `FulfillmentCancelMutation.graphql`, the fake's recording of it and
   its wire-level test. The next compile regenerates the client without the operation; there is no separate step.
5. **The documents.** `specs/fulfillment-shipment-fo-mapping.md` (phases 2 and 3, the decision table, the run-summary
   example on its line 217) and `docs/FULFILLMENT_VERIFICATION.md` ("Sync phases (validate-before-cancel)", the
   failure matrix, the idempotency notes) still describe cancel-and-recreate. Rewrite those passages to describe the
   live-remaining-quantity design the code has had since 31 August. This is the part with the most judgement in it
   and the human developer may prefer to do it by hand.


## Behavioral contract

- **Precondition**: unchanged. A `SyncShipmentsWithFulfillmentsRequest` that passes the domain validators.
- **Postcondition**: unchanged. For every shipment with at least one matched line, one fulfillment is created on
  Shopify; the answer is the list of created fulfillment ids in payload order. No existing fulfillment is touched.
- **Invariant**: the service never sends `fulfillmentCancel` to Shopify. Today this is true by accident of the
  planner; after this change it is true by construction, because the operation does not exist in the code base.
- **Observable difference**: only the `canceled=0` token disappears from one info-level log line.


## Edge cases

- **A retry after a partial failure** still works the same way: the matcher consumes live remaining quantities, so a
  shipment whose fulfillment already exists is skipped and the rest are created. The existing test
  `partial failure recovery on retry skips fulfilled variant and creates the rest` pins this.
- **The five `none { it is FulfillmentCancel }` assertions** guarded against the old behaviour returning. Once the
  variant does not exist they cannot be written, and the invariant above holds by construction, so they are dropped
  rather than replaced. A reviewer who wants a belt-and-braces check can assert that the fake records no
  `FulfillmentCancelMutation` operation, but there will be no such operation to record.
- **The Shopify scope.** Removing the operation does not change the scopes the app requests; `write_fulfillments`
  covers both creating and cancelling. Nothing to change in `SHOPIFY_SCOPES`.


## Reuse inventory

- `effectShopifyMutations`'s create branch (`src/dropnext/dss/workflow/effectShopifyMutations.kt`) is the loop body
  after the change; nothing new is written, only the `when` is removed.
- The `FulfillmentTracking` and `FulfillmentLine` types in `lib/shopify/graphql/ShopifyGraphqlService.kt` stay as
  they are.
- `TestSuiteArchitectureTest`'s per-method wire-test rule is what makes step 4 safe to do in one go: it fails the
  build if the test or the method is removed without the other.


## Test plan

Test-first per `.claude/rules/workflow.md`, cheapest flavour first.

- **Pure**: `CalculateShopifyMutationsTest` compiles against the new type and drops the five negative assertions.
- **Fake-backed**: `EffectShopifyMutationsTest` loses its two cancel cases; the remaining create cases are unchanged.
  `DetermineShopifyMutationsTest` drops its negative assertion.
- **Wire-level**: `HttpShopifyGraphqlServiceTest` loses `cancelFulfillment treats an already cancelled fulfillment as
  done`. `SyncShopifyShipmentsToFulfillmentsTest`'s summary-line test asserts the line no longer contains `canceled=`.
- **Architecture**: `ArchitectureTest` and `TestSuiteArchitectureTest` must stay green with no allowlist change.


## Open questions for the human developer

1. Keep the name `effectShopifyMutations`, or rename to say it only creates? The name is referenced from the mapping
   spec and from `CLAUDE.md` ("calculate → determine → effect").
2. Do the two documents get rewritten in this change, or in a documentation pass of their own? The code change is
   small and mechanical; the document rewrite is where the thinking is.
