# Spec: Shopify webhook deduplication

Status: draft
Author: cies (with Claude)
Date: 2026-05-24


## Problem

Shopify guarantees **at-least-once** delivery for webhooks. The same `(topic, resource)`
event can arrive on `/webhooks/shopify` multiple times for any of these reasons:

1. **Shopify retried** — the first delivery received a non-2xx, timed out, or our HTTP
   response was lost. Shopify retries up to 19 times over 48 hours.
2. **Multi-instance DSS** — once we run more than one DSS replica behind a load balancer,
   nothing prevents Shopify's retry from landing on a different instance than the first
   attempt. (Today we run a single instance; this becomes a footgun the moment we scale
   horizontally.)
3. **Operator-triggered replays** — Shopify's Partner Dashboard lets a developer resend a
   delivery, and we use this during incident recovery. We need replays to be safe.
4. **`orders/updated` after `orders/create`** — Shopify usually sends an `updated` shortly
   after `create`; with `DSS_SYNC_ORDER_ON_UPDATED=true` both call
   [`MonolithService.postCreateOrder`](../src/dropnext/dss/lib/monolith/MonolithService.kt)
   for the same `shopify_order_id`. This is **not** strictly a duplicate but the downstream
   effect on the monolith is identical, so the dedup boundary must handle it.

The visible failure modes today:

- `POST /orders` to the monolith: today it returns `409 Conflict` for duplicate order ids and
  we log "order already existed — duplicate webhook". Functional, but each duplicate still
  costs a Shopify Graphql `GetOrderForDss` + a monolith POST round-trip. Multiply by 19
  retries × N replicas and the noise gets expensive.
- `POST /product-variants` (upsert) — the monolith treats this as idempotent today
  (re-upsert with the same content is a no-op). Same comment about wasted work.
- `DELETE /product-variants` — soft-delete is idempotent.

So the *correctness* defect is mostly absorbed by the monolith's idempotency contract. The
*efficiency* defect is real and grows with replicas and Shopify retry storms. The
*observability* defect is real too: today, "we processed X" and "Shopify sent us X
deliveries" are not the same number, and we can't measure dedup rate.


## Goals

1. **At-most-once effects** in the monolith for any given Shopify webhook delivery, even
   across multiple DSS replicas.
2. **Cheap to add** — the DSS keeps no server-side database; adopting one for dedup alone
   would invert the operational model.
3. **Observable** — every drop should be logged with the dedup key so we can answer "how
   many duplicates did we filter today?"
4. **Recoverable** — a failed delivery that we accepted but did not finish processing must
   be retriable, not silently swallowed.


## Non-goals

- **Ordering guarantees.** We do not promise that webhooks process in the order Shopify
  sends them. Shopify itself does not (the order of `orders/create` and
  `orders/updated` for the same resource can race). Downstream code in the monolith already
  resolves order conflicts by `shopify_order_id` + timestamps.
- **End-to-end exactly-once.** True exactly-once across DSS + monolith would require a
  two-phase commit; we settle for at-most-once in the DSS dedup boundary plus the
  monolith's existing idempotency.


## What Shopify gives us

For every webhook delivery Shopify sets:

- **`X-Shopify-Webhook-Id`** — a UUID **stable across retries of the same logical
  delivery**. ([docs](https://shopify.dev/docs/apps/build/webhooks/best-practices#detecting-duplicate-webhook-deliveries))
  This is the canonical dedup key.
- **`X-Shopify-Triggered-At`** — RFC3339 timestamp Shopify generated the event.
- **`X-Shopify-Topic`** + **`X-Shopify-Shop-Domain`** — already consumed today.

We will **not** use `X-Shopify-Topic` + payload-id as the dedup key: Shopify documents
`X-Shopify-Webhook-Id` as the recommended primitive and it covers replays
(payload-id-based dedup would falsely treat a manual replay of the same event as a
new delivery, which is the opposite of what we want).


## Approaches considered

### A. Trust the monolith (do nothing)

What works today: monolith returns `409 Conflict` for duplicate orders. Variant
upsert/delete are already idempotent.

**Why not enough:**
- We still incur the Graphql `GetOrderForDss` round-trip before discovering the
  duplicate at the monolith. Shopify's retry storm hits Shopify's own API.
- No metric — we can't distinguish "5,000 webhooks today" from "5,000 webhooks today,
  3,200 of which were duplicates we paid for."
- Doesn't survive non-idempotent endpoints we add later
  (e.g. `customers/redact`, `app/uninstalled` — neither idempotent in the monolith).

### B. In-memory dedup cache per replica

`Caffeine` / a `ConcurrentHashMap<String, Instant>` keyed by `X-Shopify-Webhook-Id` with
a short TTL (~24h, longer than Shopify's typical retry window but shorter than the
49-hour outer retry envelope, so memory bound is finite).

**Pros:**
- Zero infrastructure cost — fits the "no server-side DB" model.
- Solves single-instance retry storms entirely.
- Trivial to ship and operate.

**Cons:**
- **Does not survive replica restarts.** A SIGTERM during a 19-retry burst lets the next
  retry land on a fresh process with an empty cache, and the deduplication does nothing.
- **Does not coordinate across replicas.** The moment we scale to 2+ replicas, any
  given retry has a 50% chance of hitting the "other" replica's empty cache.

### C. Shared dedup store (Redis)

Same key (`X-Shopify-Webhook-Id`), same 24h TTL, but in a small Redis instance that all
replicas share.

**Pros:**
- Solves multi-replica and restart-survival in one move.
- Standard pattern for webhook receivers at scale.

**Cons:**
- Adds an infrastructure dependency the project currently does not have. The repo's
  dependency policy is "prefer not to add". Redis as runtime infra (not a Gradle dep)
  needs explicit ops sign-off.
- Adds a new failure mode: if Redis is down, do we **fail closed** (return 5xx and let
  Shopify retry) or **fail open** (process anyway and risk a duplicate effect on the
  monolith)? Both have a defensible answer; we need to pick one and write it down.

### D. Push dedup into the monolith

The monolith owns the database and already enforces idempotency on `POST /orders`. Add a
`webhook_deliveries` table keyed on `(shopify_shop_id, webhook_id)` and let *every*
monolith write that descends from a Shopify webhook check it as a precondition.

**Pros:**
- Single source of truth — no risk of DSS and monolith disagreeing about what's been
  processed.
- Persists naturally with the rest of the monolith's data.
- The DSS stays stateless: it forwards `X-Shopify-Webhook-Id` and the monolith decides.

**Cons:**
- We still pay the Graphql `GetOrderForDss` round-trip on every retry, because we don't
  discover the duplicate until *after* fetching the order. (Solvable: pass the webhook id
  on a cheaper monolith pre-check before the Graphql call.)
- Cross-team coordination — the monolith team has to add a table and a check.
- Doesn't help for webhook side-effects that live entirely in the DSS
  (none today, but we might add one).


## Recommended approach

**Layered: B in the DSS, D in the monolith.** Each layer catches a different failure mode.

### Layer 1 — DSS in-memory dedup (cheap defence)

Add a Caffeine-backed cache:

```kotlin
class WebhookDeliveryCache(
  private val ttl: Duration = Duration.ofHours(24),
  private val maxEntries: Long = 100_000,
) {
  // key = "<shop-domain>|<X-Shopify-Webhook-Id>"
  // value = Instant the delivery was first accepted
  fun firstSeen(shop: ShopDomain, webhookId: String): Boolean
}
```

`ShopifyWebhookHandlers.handleShopifyWebhook` consults it **after** HMAC verification but
**before** the Graphql call. On duplicate, log `dedup=true webhookId=...` and return 200
OK without further work.

**Where it catches duplicates:** single-replica retry storms; operator replays.
**Where it falls through:** replica restart, multi-replica deployments.

Sizing: 100k entries × ~64 bytes ≈ 6 MB. Comfortable.

No new infra. Caffeine is already a transitive dependency of okhttp's interceptor cache
in many setups, but if it isn't, this would be the *first* genuine new gradle dep — needs
human sign-off (see [CLAUDE.md](../CLAUDE.md) dependency policy). Fallback: a plain
`ConcurrentHashMap` with a periodic purge task — uglier but no dep.

### Layer 2 — monolith persistent dedup (correctness floor)

Coordinate with the monolith team to add:

```
CREATE TABLE webhook_deliveries (
  shop_id              BIGINT      NOT NULL,
  webhook_id           VARCHAR(64) NOT NULL,
  topic                VARCHAR(64) NOT NULL,
  received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  outcome              VARCHAR(32) NOT NULL,  -- 'processed' | 'rejected' | 'in_flight'
  PRIMARY KEY (shop_id, webhook_id)
);
```

Add a new precondition endpoint **on the monolith** (e.g. `POST /webhook-deliveries`)
that the DSS calls **after** layer 1 says "first time" but **before** the Graphql call:

- Insert with `outcome='in_flight'`; on PK collision → respond 409.
- On 409, DSS short-circuits exactly like layer 1.
- On 200, DSS proceeds; the *real* monolith write happens later and updates the row to
  `'processed'`. If the DSS crashes between insert and the real write, a later retry sees
  `in_flight` past a staleness window (~10 minutes) and re-attempts.

**Where it catches duplicates:** everything layer 1 misses (multi-replica, restarts,
manual replays from a different operator).
**Where it falls through:** Nowhere we currently care about — a webhook id collision in
the Shopify-managed UUID would be the only escape, which we treat as impossible.

This layer is doing the heavy lifting; layer 1 just shaves off the most common case
without paying the round-trip.


## Implementation plan

The DSS-side work is implementation-light enough to ship in one PR; the monolith side
needs coordination. Suggested ordering:

1. **PR-A (DSS, in-memory only).** Adds `WebhookDeliveryCache` + `ShopifyWebhookHandlers`
   wiring. Tests: a unit test that two consecutive deliveries with the same
   `X-Shopify-Webhook-Id` only run the Graphql pipeline once; a test that the cache
   evicts after TTL; a test for HMAC-fail → no cache write. After this ships, multi-instance
   deployments will still have the failure mode — note in the PR description.
2. **PR-B (monolith).** Add the `webhook_deliveries` table + `/webhook-deliveries`
   endpoint. Backfill no data — start fresh.
3. **PR-C (DSS).** Wire the monolith pre-check in between layer 1 and Graphql. Once
   merged, layer 1's cache becomes a pure latency optimisation — correctness is owned by
   the monolith.

Rollback path: each PR is independent. PR-A by itself is the "trust the monolith plus a
small efficiency win" world we already have. PR-C without PR-B does nothing because the
endpoint doesn't exist; PR-B without PR-C is dead code in the monolith.


## Test plan

- **Unit:** `WebhookDeliveryCache.firstSeen` returns true once, false on the second call
  for the same key, true again after TTL.
- **Unit:** `ShopifyWebhookHandlers` calls Graphql + monolith zero times when a duplicate
  webhook id arrives.
- **Unit:** A delivery with a missing `X-Shopify-Webhook-Id` header is still processed
  (graceful fallback — Shopify can omit this on some legacy topics; the layer 2 PK still
  catches anything that gets through).
- **Unit:** HMAC failure does not write to the cache (so a malicious replay with a bad
  HMAC can be retried by Shopify after we fix the HMAC issue).
- **Integration (with `FakeMonolithService`):** simulate Shopify sending `orders/create`
  twice in quick succession; assert exactly one `postCreateOrder` call.


## Observability

Add a counter (Logback MDC field, since we don't ship a metrics library yet):

```
log.info { "Webhook dedup: drop topic=$topic shop=$shop webhookId=$webhookId reason=cache_hit" }
log.info { "Webhook dedup: drop topic=$topic shop=$shop webhookId=$webhookId reason=monolith_409" }
```

The two reasons let us measure the win from layer 1 alone vs. the additional catch from
layer 2.


## Open questions for the team

1. **Fail-open vs fail-closed on layer 2 outage.** When the monolith pre-check is
   unreachable, do we process the webhook and risk a duplicate (fail open), or return 5xx
   so Shopify retries (fail closed)? Fail-closed is safer; fail-open keeps DSS resilient
   to monolith blips. **Recommendation: fail-closed.**
2. **Operator replay UX.** Do we want a "force reprocess" hatch in the monolith for
   incident recovery — i.e., a way to delete a `webhook_deliveries` row so the next replay
   goes through?
3. **TTL on layer 1.** 24h covers Shopify's first ~10 retries. 48h covers all of them.
   Memory cost difference is negligible; choose 48h to match Shopify's outer retry
   envelope.
4. **What about deletions?** `products/delete` doesn't return a `webhook_id` on Shopify's
   side in some legacy payload formats — confirm before relying on the dedup key for it.


## What this spec does not cover

- **Outbound retries to the monolith.** When the monolith returns 5xx, the current code
  has a single attempt (see TODO in [CLAUDE.md](../CLAUDE.md) line 144). That's a
  separate concern handled by `createMonolithHttpClient` (already has retry config).
- **Quota / rate-limit handling on the Shopify Graphql call.** Today we surface throttled
  responses as `FulfillmentResult.Err.GraphqlError`; not changing.
- **`app/uninstalled` and GDPR webhooks.** When we add handlers for those, the
  dedup boundary above covers them automatically — but the *response* policy
  (drop tokens, schedule deletion, etc.) is its own design.
