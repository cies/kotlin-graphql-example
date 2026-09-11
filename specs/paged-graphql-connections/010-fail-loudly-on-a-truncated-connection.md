# Spec: fail loudly when a Graphql connection was truncated

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: nothing. Prerequisite for `020`, and for any future "prune the variants absent from the payload"
logic in the monolith: without it, a truncated page turns into a deletion.
Repos: DSS only.


## Problem

Every Graphql connection the service reads is fetched with a fixed `first` and no `pageInfo`, so nothing can
tell a complete list from the first page of a longer one:

| Operation | Connection | `first` | What silently happens past the limit |
|---|---|---|---|
| `GetProductById` | `product.variants` | 100 | The `products/create` and `products/update` webhooks upsert the first 100 variants; the rest never reach the monolith. Shopify allows 2048 variants per product. |
| `GetProductById` | `product.media` | 20 | Images beyond the twentieth are not sent. Cosmetic. |
| `GetOrderForDss` | `order.lineItems` | 100 | The `orders/create` webhook creates the order with its first 100 lines. |
| `GetOrderForDss` | `order.fulfillmentOrders` | 50 | The matcher reports "no open fulfillment order" for lines that live on a fulfillment order it never saw, and `/sync-shipments-with-fulfillments` skips them as unmatched. |
| `GetOrderForDss` | `fulfillmentOrders.lineItems` | 100 | Same, per fulfillment order. |
| `GetOrderForDss` | `order.fulfillments` | 50 | Not a connection (`fulfillments(first:)` answers a plain list), so it has no `pageInfo`; a tracking number on the fifty-first fulfillment is not found. |
| `GetWebhookSubscriptions` | `webhookSubscriptions` | 100 | Filtered to our topics and our callback URI, so a handful at most. No change. |
| `SyncProductsPage` | `products` | `$first` | Already asks for `pageInfo`; only the count of one page is used. No change. |

`variants` is the one limit a real catalogue reaches (`020` pages it). The others are rare, but when they
happen the service answers `200` with a wrong mirror, which is the failure mode a sync must never have.


## What changes

- Every connection in the table above except the two "no change" rows asks for `pageInfo { hasNextPage }`.
  `variants.media(first: 1)` is deliberately the first image and is left alone.
- `HttpShopifyGraphqlService` checks `hasNextPage` on every connection it answers, in the method that owns
  the payload (`productById`, `orderForDss`), before the snapshot leaves `lib/shopify/`. This is the triage
  point the Graphql rules name; a workflow never sees `pageInfo`.
- A new sealed member `ShopifyError.Truncated(val connection: String, val pageSize: Int)`, with a message like
  `Shopify has more product.variants than the 100 this service loads`. It is the answer of `productById` when
  `variants` has a next page, and of `orderForDss` when `lineItems`, `fulfillmentOrders` or any fulfillment
  order's `lineItems` has one.
- `product.media` is the exception: a next page is logged at `warn` (`Product media truncated productGid=…
  first=20`) and the product is mirrored with the images it has. An image count must not stop a variant sync.
- `order.fulfillments(first: 50)` cannot report truncation. Raise it to 250, Shopify's maximum for this
  argument, and document the remaining limit in `docs/` (`040` of the tracking-number folder rewrites that
  document; this spec only adds the sentence).
- Mapping the failure:
  - `WebhookMirrorOutcome.isTransient` is `false` for `Truncated`: a redelivery loads the same product. The
    handler answers `200` and the `WebhookDeliveryReport` line is at `error` level with `error=truncated`,
    which is what an operator searches for.
  - `ShopifyError.toDssError()` maps `Truncated` to a new `DssError.Unsupported` answered as `500`, with the
    same generic message the `Internal` error uses ("internal error"); the connection and the page size go to
    the log at `error`. Not a `502`: the monolith would retry into the same answer. Not a `400`: nothing in
    the request is wrong. The monolith's `SyncShipmentsWithFulfillments` job treats a `5xx` as a retry, which
    keeps the shipments unsynced and visible until a human acts; that is the intended outcome.


## Behavioral contract

- **Precondition**: a Shopify response that decoded. Truncation is checked after the existing triage (transport,
  top-level `errors`, `NotFound`), so a `Truncated` answer means the resource exists.
- **Postcondition**: a `Success` from `productById` carries every variant of the product, and a `Success` from
  `orderForDss` carries every line item and every fulfillment order with every line, or the call answers
  `Truncated` and nothing downstream runs on a partial snapshot.
- **Invariant**: no `200` to Shopify and no `200` to the monolith is ever computed from a truncated list.
- **Unchanged**: the page sizes stay as they are in this spec; `020` changes how variants are loaded.


## Edge cases

- `hasNextPage` is `true` on a nested connection only (one fulfillment order with more than 100 lines): the
  whole `orderForDss` answers `Truncated("fulfillmentOrders.lineItems", 100)`; the name says which level.
- A product with exactly 100 variants: `hasNextPage` is `false`, a normal sync.
- The `products/delete` webhook never loads the product, so it is unaffected; a shop whose product could not
  be synced because of truncation still gets its deletes.
- `media` truncated and `variants` truncated on the same product: `Truncated` wins, the media warning is not
  logged (the sync did not happen).
- The install page's product sample (`SyncProductsPage`, `first = 3`) is a count and never fails on paging.


## Reuse inventory

- `ShopifyError` and the `isTransient` mapping in `workflow/WebhookMirrorOutcome.kt`; the new member joins the
  non-transient branch beside `UserError` and `NotFound`.
- `handler/toDssError.kt`: the one `ShopifyError` → `DssError` mapping.
- `lib/ktor/DssError.kt`: `Internal` is the model for a `500` whose message stays generic.
- `HttpShopifyGraphqlService.execute` and the payload-owning methods: the triage point.
- `WebhookDeliveryReport`: the `error=` label is derived from the `ShopifyError` type; a new type needs its
  label (check `WebhookDeliveryReportTest` for the naming of the existing ones).
- Fixtures: `minimalOrder()`, `orderWithFulfillment()`, `diagramCrossFoOrder()`, `openFulfillmentOrder()`
  (`testutil/fixture/`) and `sampleProduct()` build the generated types and gain the `pageInfo` field with a
  `hasNextPage = false` default; the canned JSON in `FakeShopifyGraphqlServer` gains the same key.


## Test plan

- **Wire** (`HttpShopifyGraphqlServiceTest`): `productById` with `variants.pageInfo.hasNextPage = true` answers
  `Failure(Truncated("product.variants", 100))`; with `media` truncated only, `Success` plus the warn line;
  `orderForDss` with a nested `fulfillmentOrders.lineItems` next page answers the nested name.
- **Fake-backed** (`SyncShopifyProductToMonolithTest`): a `Truncated` failure is `ShopifyFailed`, not transient,
  and the monolith is not called.
- **Request → response**: `ShopifyWebhookHandlersTest`: `products/update` on a truncated product is `200` with
  `error=truncated` in the body and an `error`-level line; `MonolithWebhookHandlersTest`: `sync-shipments` on an
  order with truncated fulfillment orders is `500` with the generic message and no `createFulfillment` call.
- **Pure**: `WebhookMirrorOutcome.isTransient` for `Truncated` is `false`; `toDssError` maps it to the `500`.


## Open questions for the human developer

1. `500` for the monolith-facing routes, as written, or `502` so the monolith's existing retry stays the
   only path (and keeps retrying into the same answer until someone notices the log)?
2. Should `media` truncation stay a warning, or is a product with more than twenty images rare enough to
   fail too and keep the rule uniform?
