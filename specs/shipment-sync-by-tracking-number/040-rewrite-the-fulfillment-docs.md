# Spec: make the fulfillment documents describe the design that ships

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `010`, `020`, `030` (write it once, after they land).
Repos: DSS; one page in the monolith.


## Problem

Three documents describe the cancel-and-recreate design of July 2026 as current:

- `specs/fulfillment-shipment-fo-mapping.md` (status "approved"): phases 2 and 3, the decision table, the
  "Split-change and idempotent retry strategy" and the run-summary example. It also names
  `docs/openapi/dss-api.yaml` as the canonical contract; that file does not exist, the checked-in
  `src/resources/monolith-dss-openapi.json` is.
- `docs/FULFILLMENT_VERIFICATION.md`: "Sync phases (validate-before-cancel)", the failure matrix, and
  checklist items 4 (idempotent retry) and 5 (reorganised splits) describe behaviour the code has not had
  since 2026-08-31, and will have again only in the tracking-number form of `020`.
- `CLAUDE.md` ("Monolith-facing endpoints" table) says the sync "reconciles DropNext shipments with the
  order's Shopify fulfillment orders", which is true but says nothing about what a re-send or a split does.

An operator following the verification checklist today verifies behaviour that does not exist.


## What changes

- **Delete** `specs/fulfillment-shipment-fo-mapping.md`. Specs are ephemeral by the workflow rules, and its
  useful content (the entity glossary, the fulfillment-order boundary diagram, the defensive rules) moves
  into `docs/FULFILLMENT_VERIFICATION.md` or a new `docs/FULFILLMENT_SYNC.md`; the rest is superseded by this
  folder.
- **Rewrite** `docs/FULFILLMENT_VERIFICATION.md` around the design as landed:
  - the phases: validate → load the order → skip already-fulfilled shipments by tracking number → plan
    cancels for replaced tracking numbers → credit the ledger → plan creates against live remaining
    quantity → effect cancels then creates → answer the per-shipment report;
  - the failure matrix with the real answers (a refused cancel is a `400`, a transport failure a `502`, what
    a retry does in each case);
  - the "Idempotent retry" checklist item says: a re-sent shipment is reported `already_fulfilled`, nothing is
    created twice; the "Reorganised splits" item says: the replaced tracking numbers are cancelled, the new
    ones created;
  - the example summary log line with the fields the code prints after `020`;
  - the known limitations that remain: a shipment line larger than any single open fulfillment-order line is
    refused; tracking events on a cancelled fulfillment are lost; `first: N` truncation on the order query.
- **Update** `CLAUDE.md`'s table row for `POST /sync-shipments-with-fulfillments` to one sentence naming the
  tracking-number reconciliation, and the "Outcome types" naming entry to list `ShipmentSyncReport`.
- **Monolith**: the supplier portal's split page copy. "Submitting will cancel the current shipment(s) and
  create new ones" becomes true with `020`; the sentence about shipping-notification e-mails is removed or
  made true per `020`'s open question 3.


## Reuse inventory

- The glossary and the mermaid diagrams in `specs/fulfillment-shipment-fo-mapping.md` are worth keeping
  verbatim where they still hold (the fulfillment-order boundary diagram does; the sequence diagram does not).
- `000-analysis-cancel-and-recreate.md` in this folder has the split-flow sequence diagram for the *broken*
  state; the docs need its fixed counterpart.


## Test plan

None: documentation only. The one check is that every code identifier the docs name exists (`grep` each
function and file name), since the current docs fail that test.


## Open question for the human developer

Keep one document (`FULFILLMENT_VERIFICATION.md`, as today) or split it into a design document and a
verification checklist? The checklist is what an operator opens; the design is what a developer opens.
