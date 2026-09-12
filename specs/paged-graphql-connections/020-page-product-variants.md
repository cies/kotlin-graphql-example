# Spec: page a product's variants

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `010` (a page that could not be completed must fail the same way a truncated one does).
Repos: DSS only.


## Problem

After `010` a product with more than 100 variants fails its webhook loudly instead of silently. It still does
not reach the monolith. Shopify allows 2048 variants per product for every merchant; a fashion or furniture
catalogue reaches several hundred. This is the one limit of `010`'s table that a real shop hits, so it gets paging.


## What changes

- A new operation `src/resources/GetProductVariantsPage.graphql`:

  ```graphql
  query GetProductVariantsPage($id: ID!, $first: Int!, $after: String) {
    product(id: $id) {
      variants(first: $first, after: $after) {
        pageInfo { hasNextPage endCursor }
        edges { node { …the variant fields `GetProductById` selects today, including `media(first: 1)`… } }
      }
    }
  }
  ```

- `GetProductById.graphql` drops its `variants` block. The product header (title, vendor, media, currency) and
  the variants are then loaded by two operations, always: one Kotlin type for a variant instead of one per
  operation, which is what keeps the mapper to one signature. The cost is one extra round trip per product
  webhook; a product webhook is not latency-sensitive.
- `ShopifyGraphqlService` gains one single-shot primitive, as the Graphql rules require:
  `productVariantsPage(productGid: String, first: Int, after: String?): ShopifyResult<ProductVariantsPage?>`,
  where `ProductVariantsPage(val variants: List<ProductVariant>, val nextCursor: String?)` is our own type
  wrapping the generated variant nodes (`nextCursor` is `endCursor` when `hasNextPage`, else `null`), and
  `null` means the product is gone. `productById` keeps answering the header as `ShopProduct`.
- The loop is a workflow, `workflow/loadShopifyProductVariants.kt`:
  `suspend fun loadShopifyProductVariants(shopify, productGid): ShopifyResult<List<ProductVariant>>`, calling
  the primitive with `first = 100` until `nextCursor` is `null`, and answering
  `ShopifyError.Truncated("product.variants", MAX_VARIANT_PAGES * 100)` after `MAX_VARIANT_PAGES = 25` pages
  (2048 variants fit in 21). The cap exists so a cursor that never ends cannot loop forever.
- `syncShopifyProductToMonolith` loads the header, then the variants, then maps. A failure of either call is
  the workflow's answer as today; a product that disappears between the two calls is `PRODUCT_GONE`.
- `mapper/toProductVariantItems.kt` takes the variant list and the shop currency instead of the `Product`;
  the product-level fields it reads today (title, vendor, product image) come in as parameters or as the
  `ShopProduct` header beside the list. The mapper's tests move with it.
- `ArchitectureTest.graphqlGeneratedAllowList` needs no change. `ProductVariantsPage` lives in
  `lib/shopify/graphql/` beside `ShopProduct` and wraps the generated variant node, so its readers see a generated
  type: the new workflow in `workflow/` and the mapper in `mapper/`, and both packages are already on the list.


## Behavioral contract

- **Precondition**: `010` is in place, so a partial list is never handed on.
- **Postcondition**: a mirrored product carries all its variants, in Shopify's order, across pages.
- **Invariant**: the number of Graphql calls for a product is `1 + ceil(variants / 100)`, at most 26.
- **Idempotence**: unchanged; the monolith's upsert is by variant id.


## Edge cases

- **Zero variants** (Shopify always has at least the default variant, but the API allows an empty page):
  one page, an empty list, the existing `NO_MAPPABLE_LINES` skip.
- **Exactly 100 variants**: `hasNextPage` is `false` on the first page, one call.
- **A page fails midway** (throttled on page three): the whole load fails with that page's error; the webhook
  answers `502` through the existing transient mapping and Shopify redelivers. Nothing was sent to the
  monolith, so a redelivery starts clean.
- **The product is deleted between pages**: `productVariantsPage` answers `null` on the next page, the
  workflow answers `PRODUCT_GONE`.
- **Throttling**: Shopify sizes the query cost of a connection by its `first` argument, refuses a single query
  that costs more than 1,000 points, and restores 100 points per second on a standard plan (200 on Advanced,
  1,000 on Plus, 2,000 on Enterprise). A 2048-variant product is 22 calls in a burst. The `extensions.cost` of a
  response reports what a page really costs; measure one on a real shop before settling the page size (open
  question 2). A throttled page is a transient `ShopifyError.HttpError(429)` or `GraphqlError`, so the webhook
  answers `502` and Shopify redelivers; no backoff is added here. If real shops hit this, a delay between pages is a
  one-line follow-up.


## Reuse inventory

- Shopify's cursor pagination (`$first`, `$after`, `pageInfo { hasNextPage endCursor }`) is the model for the new
  operation's variables; no operation in the service pages today, so there is none to copy.
- `HttpShopifyGraphqlService.productById` for the `null`-means-gone shape; `execute` for the triage.
- `FakeShopifyGraphqlService`: a `productVariantsPageResultQueue` in the style of the existing `…ResultQueue`
  fields, so a test can serve two pages and then a failure.
- `sampleProduct()` in `testutil/fixture/ProductFixtures.kt` and the canned product JSON in
  `FakeShopifyGraphqlServer` split into a header and a variants page.


## Test plan

- **Wire** (`HttpShopifyGraphqlServiceTest`): `productVariantsPage` deserializes a non-empty page and answers
  `nextCursor` when `hasNextPage`, `null` when not; `TestSuiteArchitectureTest` demands this test by name.
- **Fake-backed** (`LoadShopifyProductVariantsTest`): two pages are concatenated in order; a failure on the
  second page is the answer; the page cap answers `Truncated`; a `null` page answers `null`.
- **Fake-backed** (`SyncShopifyProductToMonolithTest`): a 150-variant product reaches the monolith as 150
  variant items in one upsert.
- **Pure** (`ToProductVariantItemsTest`): unchanged cases against the new signature.


## Open questions for the human developer

1. Two operations always (this spec) or keep the first 100 variants in `GetProductById` and page only the
   rest? The second saves a round trip for most products and costs a second variant type in the mapper.
2. Is 100 per page right, or should the page be 250, which makes the 2048 case 9 pages? The cost of a page grows
   with `first`, including the nested `media(first: 1)` of every variant, and must stay under the 1,000-point cap
   of a single query; measure before raising it.


## Sources

- [Shopify changelog: the product variant limit is now 2048 for all merchants](https://shopify.dev/changelog/the-product-variant-limit-is-now-2048-for-all-merchants).
- [Shopify API limits](https://shopify.dev/docs/api/usage/limits): the restore rate per plan, the 1,000-point cap
  of a single query, and connection costs sized by `first`.
