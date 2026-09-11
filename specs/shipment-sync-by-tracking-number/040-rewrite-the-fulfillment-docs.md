# Spec: make the fulfillment documents describe the design that ships

Status: draft
Author: cies (with Claude)
Date: 2026-09-11, revised 2026-09-11 (the July spec is already deleted; the verification doc describes the
additive sync as it runs).
Depends on: `010`, `020`, `030` (write it once, after they land).
Repos: DSS; comments and one page in the monolith.


## Problem

Once `010` to `030` have landed, these still describe the sync as it ran before them:

- `docs/FULFILLMENT_VERIFICATION.md` describes the additive sync of 2026-08-31 (no cancels, matching against live
  remaining quantity), lists the re-send duplicate, the unmirrored split and the silent partial failure as known
  gaps, and opens with a temporary note that names this folder's specs. The workspace rule keeps spec names out of
  docs; the note is allowed only until this spec lands.
- `CLAUDE.md` ("Monolith-facing endpoints" table) says the sync "reconciles DropNext shipments with the order's
  Shopify fulfillment orders", which is true but says nothing about what a re-send or a split does, and its
  "Outcome types" naming entry does not list the report `030` adds.
- The monolith's comments on `DssWebhookService.syncShipmentsWithFulfillments` and on the
  `SyncShipmentsWithFulfillments` job say the DSS "treats a repeat as an update" (`000`, "What the monolith already
  promises").
- The supplier portal's split page promises a cancel that only `020` makes true, and e-mails that nothing sends.

The July spec, `specs/fulfillment-shipment-fo-mapping.md`, was deleted on 2026-09-11. Its glossary and its
fulfillment-order boundary diagram moved into `docs/FULFILLMENT_VERIFICATION.md` then, corrected to live remaining
quantity.


## What changes

- **Rewrite** `docs/FULFILLMENT_VERIFICATION.md` around the design as landed:
  - the phases: validate → load the order → skip already-fulfilled shipments by tracking number → plan cancels
    for the replaced tracking numbers and check per variant that the new shipments fit → effect the cancels →
    reload the order → plan the creates against live remaining quantity → effect the creates → answer the
    per-shipment report. Without replaced tracking numbers the order is loaded once and the cancel steps are empty;
  - the failure matrix with the real answers (a refused cancel or create is a `400`, a transport failure a `502`),
    what the report body says in each case, and what a retry does;
  - the "Idempotent retry" checklist item: a re-sent shipment is reported `already_fulfilled`, nothing is created
    twice; the "Reorganised splits" item: the replaced tracking numbers are cancelled, the new ones created;
  - the example summary log line with the fields the code prints after `020` and `030`;
  - the known limitations that remain: a shipment line larger than any single open fulfillment-order line is
    refused; tracking events on a cancelled fulfillment are lost; the `first: N` limits of the order query,
    including the sentence `specs/paged-graphql-connections/010-fail-loudly-on-a-truncated-connection.md` adds
    about `fulfillments(first: 250)` when that spec has landed;
  - **remove** the temporary note, the "Known gaps" section and every other reference to a spec.
- **Update** `CLAUDE.md`'s table row for `POST /sync-shipments-with-fulfillments` to one sentence naming the
  tracking-number reconciliation, and the "Outcome types" naming entry to list `ShipmentSyncReport`.
- **Monolith**:
  - the comments on `DssWebhookService.syncShipmentsWithFulfillments` and on the job: a re-sent shipment is
    skipped by its tracking number; a split's replaced tracking numbers are cancelled and the new shipments
    created;
  - the supplier portal's split page copy (`createShipmentSplitPage.kt`): "Submitting will cancel the current
    shipment(s) and create new ones" becomes true with `020`; the sentence about shipping-notification e-mails is
    removed or made true per `020`'s open question 3.


## Reuse inventory

- The glossary and the boundary diagram now in `docs/FULFILLMENT_VERIFICATION.md` hold for the landed design as
  they are.
- `000-analysis-cancel-and-recreate.md` in this folder has the split-flow sequence diagram for the *broken*
  state; the doc needs its fixed counterpart, with the reload between the cancels and the creates.


## Test plan

None: documentation only. Two checks: every code identifier the docs name exists (`grep` each function and file
name), and `grep -rn 'specs/' docs CLAUDE.md README.md` finds nothing.


## Open question for the human developer

Keep one document (`FULFILLMENT_VERIFICATION.md`, as today) or split it into a design document and a
verification checklist? The checklist is what an operator opens; the design is what a developer opens.
