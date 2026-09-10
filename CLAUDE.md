# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.ai/code) when working with code in this repository.

Companion project: [`dropnext-monolith`](../dropnext-monolith), a sibling checkout. Conventions here are
intentionally kept uniform with that repo where it makes sense. What spans the two repos — the DSS ↔ monolith
contract, deploying, the sibling layout — is described in the workspace-level [`../CLAUDE.md`](../CLAUDE.md).


# Development commands

```bash
# Full build with tests
./gradlew build

# Build without tests
./gradlew build -x test

# Run application (human-only, see "Operational boundary")
./gradlew run

# Run tests only (add `-Ptest.htmlReport` for the HTML report; the XML the IDEs read is always written)
./gradlew test

# Clean build (removes all build artifacts)
./gradlew clean

# Coverage report (opt-in: without `-Pcoverage` the JaCoCo agent stays detached), lands in `build/reports/jacoco/test/`
./gradlew jacocoTestReport -Pcoverage

# Re-download the Shopify Admin Graphql schema (after bumping the API version, see `.claude/rules/graphql.md`).
# The only task that reaches out to Shopify: a normal build compiles against the committed schema.
./gradlew graphqlIntrospectSchema

# Re-generate the typed Kotlin Graphql client from the `.graphql` files in `src/resources/`
./gradlew graphqlGenerateClient

# Re-generate the monolith DTOs and `OutBoundMonolithPaths` from `src/resources/monolith-dss-openapi.json`
# (both also run automatically as a dependency of `compileKotlin`)
./gradlew openApiGenerate generateOutBoundMonolithPaths
```


# IntelliJ `MCP Server` integration

Assume we run IntelliJ with `MCP Server` enabled and the following Run Configurations (checked in):

* `.idea/runConfigurations/dss__BUILD_.xml` (`dss [BUILD]`) — runs the Gradle `build` task
* `.idea/runConfigurations/dss__TEST_.xml` (`dss [TEST]`) — runs the Gradle `test` task

Use this MCP to run the tests; you can also run them from `bash`, but that is usually slower. You choose.
There is no `[APP]` configuration: running the service needs a public HTTPS origin (a tunnel) and real Shopify
credentials, so the human developer starts it — see "Operational boundary".


# Development environment

- Copy `.env.example` to `.env` and fill in `SHOPIFY_APP_CLIENT_ID`, `SHOPIFY_APP_CLIENT_SECRET`, `SHOPIFY_SCOPES`
  and `DSS_BASE_URL` (a public HTTPS origin, e.g. an [ngrok](https://ngrok.com/) tunnel: Shopify requires HTTPS for
  OAuth and webhook callbacks).
- The full env-var table is in [README.md](./README.md) ("Environment variables"); `Config.from(env)` in
  `config/Config.kt` is the one place that reads it (`main` layers the local `.env` file over the process environment,
  `ArchitectureTest` forbids reading the environment anywhere else). A variable not read there is not a variable.
- The service is stateless: no database, no migrations. Per-shop Shopify Admin tokens live in an in-memory
  `InMemoryShopTokenStore` (the `ShopTokenStore` interface), seeded from `DSS_SHOP_ACCESS_TOKENS`, filled by the OAuth
  install or by the monolith through `PUT /stores/api-key`, with a fallback lookup on the monolith (`GET /stores`) on a
  miss.


# Architecture overview

## Technology stack
- **Language**: Kotlin 2.3.21 on JVM 25 (the Gradle toolchain downloads it).
- **Web framework**: [Ktor](https://ktor.io/) server on the CIO engine; `dssModule.kt` installs the plugins (from `lib/ktor/`) and the routes.
- **HTTP client**: Ktor client on OkHttp. One shared client (`createSharedHttpClient()` in
  `lib/ktor/httpClientBuilders.kt`) plus a derived monolith client with retries; `ArchitectureTest` forbids
  constructing an `HttpClient(...)` anywhere else.
- **Graphql client**: [graphql-kotlin](https://github.com/ExpediaGroup/graphql-kotlin) — typed at compile time against
  the Shopify Admin schema (see `.claude/rules/graphql.md`).
- **Monolith contract**: OpenAPI. The DTOs (`dropnext.dss.contract`) and the outbound path constants are generated
  (`openapi-generator` and our own `generateOutBoundMonolithPaths` task) from the checked-in copy of the monolith's spec.
- **Serialization**: `kotlinx.serialization` (no reflection) through two shared configs, `AppJson` (inbound) and
  `MonolithJson` (outbound) in `lib/json/`; `ArchitectureTest` forbids an ad-hoc `Json {}` elsewhere.
- **Results**: [result4k](https://github.com/fork-handles/forkhandles/tree/trunk/result4k) `Result<T, E>`: every
  outbound call answers `ShopifyResult<T>` or `MonolithResult<T>`, whose sealed errors are the only failure
  vocabulary; see "Error handling".
- **Logging**: kotlin-logging over Logback (`src/resources/logback.xml`), with a per-request `trace_id` in the MDC:
  Ktor's `CallId` plugin mints or adopts it, `CallLogging`'s `callIdMdc` carries it across suspension points, and the
  client-side `CallId` plugin on the monolith client forwards it as `X-Trace-Id`. Console always;
  Logflare too when `LOGFLARE_SOURCE_NAME` and `LOGFLARE_API_KEY` are set, which is what puts a DSS line beside the
  monolith line it caused. See "Logging".
- **Testing**: JUnit 5 with Kotlin power-assert, Konsist for architecture tests, hand-written fakes (no mocking library).

## Project structure
```
/src/dropnext/dss                # Application code (Kotlin)
├── app.kt                       # `main`: reads `Config` (env + `.env`), builds the dependency graph, starts the server with `dssModule`; Ktor's own shutdown hook stops it
├── dssModule.kt                 # `Application.dssModule(deps)`: the one composition root — plugins and every route family; `main` and the tests both install it
├── dependencies.kt              # `DssDependencies` + `dssDependencies(config, …)`: the whole object graph, built once; tests override collaborators (see "Dependency wiring")
├── config/                      # `Config.from(env)` (one flat data class holding every env var, secrets wrapped) and the `.env` reader
├── domain/                      # The vocabulary every layer shares, depending on nothing of ours: `ShopDomain`, the ids and secrets (value classes), the reports the install page renders
│   └── fulfillment/             # Pure logic over an order snapshot: the fulfillment-order matcher and quantity ledger, request validation
├── handler/                     # Ktor handlers, one `*Handlers` class per route family: receive the (already decoded and validated) request, resolve the shop, call a workflow, map its answer (`toDssError`) onto the response
├── routing/                     # One `Route.*Routes(handlers)` per handler family; nothing but path ↔ handler bindings
├── path/                        # `Paths`: every inbound path constant (routes, log lines and the webhook callback URL all read it)
├── mapper/                      # Shopify snapshot → monolith contract DTO: the order and product mappers and the money helpers
├── presentation/                # Pure view layer: a report in, HTML string out; no Ktor or Shopify-schema types (`ArchitectureTest` checks)
├── workflow/                    # Orchestrations composing service primitives: shop install, order and product sync, shipment ↔ fulfillment sync (`calculate` → `determine` → `effect` mutations), tracking events, webhook registration, the token-store fallback; no Ktor server types
└── lib/                         # Stand-alone building blocks; may depend on `domain` and `contract`, never on an application package nor on each other (`ArchitectureTest` checks)
    ├── shopify/                 # Shopify protocol primitives: GIDs, `oauth/` (client + outbound OAuth paths), `webhook/` (HMAC verifier, topics, shop and id parsers), `graphql/` (`ShopifyGraphqlService` with typed results + `Http…` impl, the service factory), `token/` (`ShopTokenStore` + the in-memory store)
    ├── monolith/                # The other direction: `MonolithService` + `HttpMonolithService` answering `MonolithResult`, error-body parsing, structured failure logging
    ├── json/                    # `AppJson` and `MonolithJson`
    ├── crypto/                  # The one HMAC-SHA256 and constant-time compare
    ├── logging/                 # The MDC trace-id key (`callIdMdc` and `logback.xml` agree on it) and `currentTraceId()`
    ├── logflare/                # `LogflareAppender` (event → JSON) and `LogflareBatchSender` (queue, schedule, shipping)
    └── ktor/                    # Ktor server glue: one `install*` function per plugin (call id, call logging, status pages, JSON, request validation, the monolith bearer auth), `DssError` + `respondError`, HTTP client builders

/src/resources                   # `logback.xml`, the `.graphql` operations, `monolith-dss-openapi.json` (the checked-in copy of the monolith's spec)
/src/graphql-schema              # Introspected Shopify Admin schema (committed; regenerated by `graphqlIntrospectSchema`)
/build/generated                 # Never edit: the Graphql client (`dropnext.graphql.generated`), the contract DTOs (`dropnext.dss.contract`) and `OutBoundMonolithPaths`
/test/dropnext/dss               # Mirrors `src/`, plus `testutil/{fake,fixture,helper}/` for everything that is not a test
/docs                            # Project documentation
/specs                           # Specs produced by AI agents developing non-trivial changes
```

## Inbound Shopify webhook flow
1. Shopify POSTs to `/webhooks/shopify` (`shopifyWebhookRoutes` → `ShopifyWebhookHandlers.handleShopifyWebhook`).
2. The raw body is verified against `X-Shopify-Hmac-Sha256` with the app secret
   (`ShopifyHmacVerifierService.verifyWebhook`, constant-time compare); a mismatch is a `401`.
3. The shop comes from `X-Shopify-Shop-Domain` (or the body) and `ShopifyGraphqlServiceFactory.forShop` resolves its
   Admin token. No token → logged as an error but answered `200`, so Shopify does not keep retrying a delivery we
   cannot act on.
4. Dispatch on `ShopifyWebhookTopic`, each case one workflow function:
   - `products/create`, `products/update`: `syncShopifyProductToMonolith` — `productById`, map with
     `toProductVariantItems`, upsert the variants on the monolith (`upsertProductVariants`).
   - `products/delete`: `deleteShopifyProductFromMonolith` — variant ids from the body, soft-delete them on the
     monolith (`deleteProductVariants`).
   - `orders/create`: `syncShopifyOrderToMonolith` — `orderForDss`, `orderToCreateShopifyOrderRequest`,
     `postCreateOrder` (a `409` from the monolith is `CreateOrderOutcome.AlreadyExisted`, a success).
   - `orders/updated`: acknowledged, not mirrored (the `create` already carried the order).
   - Anything else: logged and acknowledged.

Deduplication of Shopify's at-least-once delivery is **not implemented**: the service runs as a single instance and
relies on the monolith's idempotent handling.

## Monolith-facing endpoints (DSS internal REST)
Mounted by `monolithWebhookRoutes` inside `authenticate(MONOLITH_WEBHOOK_AUTH)`: every request carries
`Authorization: Bearer <DSS_API_KEY>`, compared in constant time by the `bearer` provider that
`lib/ktor/installMonolithWebhookAuth.kt` installs, so the handlers never check auth themselves. A missing or wrong
token is the RFC 6750 challenge: a bare `401` with `WWW-Authenticate: Bearer realm=dss-internal`. A body that does not
decode or does not pass the domain validators is a `400` shaped by `StatusPages` before the handler's `receive` returns
(see "Error handling").

| Route | Body | Does |
|---|---|---|
| `POST /sync-shipments-with-fulfillments` | `SyncShipmentsWithFulfillmentsRequest` | `syncShopifyShipmentsToFulfillments`: reconciles DropNext shipments with the order's Shopify fulfillment orders (calculate → determine → effect the mutations). |
| `POST /tracking-update` | `TrackingUpdateRequest` | `syncShopifyTrackingEvent`: a tracking status becomes a Shopify `FulfillmentEvent`. |
| `PUT /stores/api-key` | `UpdateStoreApiKeyRequest` | Caches the shop's Admin token in memory and forwards it to the monolith. |

The other inbound routes are the OAuth pair (`/install`, `/oauth/callback`) and the diagnostics endpoints (`/`,
`/health`, `/api`, `/api/check`, `/api/redirect-url`); all of them are constants in `path/Paths.kt`.

The canonical contract is the monolith's `/openapi.json`, checked in here as `src/resources/monolith-dss-openapi.json`;
the DTOs (`dropnext.dss.contract`) and `OutBoundMonolithPaths` are generated from it — never
hand-write either (`ArchitectureTest` checks). Refreshing the copy is a manual step done in the same work session as
the monolith change (see `../CLAUDE.md`, "Cross-repo couplings").

## OAuth install flow
`GET /install?shop=…` redirects to Shopify's authorize URL with a signed `state`. `GET /oauth/callback` verifies the
callback HMAC and the state, exchanges the code for an Admin token (`ShopifyOAuthService`) and hands the rest to the
`installShop` workflow: learn the shop's canonical domain and id, remember the token in the `ShopTokenStore`, persist
it to the monolith (`putStoreApiKey`), sample the catalogue, register the webhook subscriptions
(`registerShopifyWebhooks`: scans what exists, registers the handled topics with the fields each topic declares,
reports active/added/failed) and answer a `ShopInstallReport` that `renderOAuthInstallPage` renders. Nothing after
the exchange fails the install; each step reports on the page instead. Failures in this flow are plain-text errors
(`respondTextError`), not JSON.

## Outbound monolith integration
`HttpMonolithService` is the single HTTP boundary to the monolith; `MonolithService` is its interface and
`FakeMonolithService` (in `test/`) the recording fake.

- Base URL: `MONOLITH_BASE_URL` (+ optional `MONOLITH_API_PREFIX`); the paths come from the generated `OutBoundMonolithPaths`.
- Auth: optional `MONOLITH_API_KEY`, sent as a Bearer token.
- Retries: the monolith client (`createMonolithHttpClient`) retries on `IOException` up to 3 times with exponential
  backoff (base 2, max 4 s). Shopify Graphql and OAuth traffic deliberately use the base client without retries, so a
  non-idempotent mutation is never sent twice.
- `DSS_ALLOW_INSECURE_MONOLITH=true` is **dev-only** — production rejects a non-HTTPS monolith URL at startup.
- Every method returns a `MonolithResult<T>` whose `MonolithError` is either `Transport` (no answer) or `Rejected`
  (status plus the parsed `MonolithErrorBody`, including the monolith's `trace_id`). Log it through
  `logMonolithFailure` (transport and 5xx → error, otherwise warn) so the two services' logs can be correlated.
- Every request carries our `trace_id` as `X-Trace-Id`, so the monolith's log lines for a call can be found from ours.

## Dependency wiring
There is no request context and no service locator; everything reaches a handler through its constructor.

- `dssDependencies(config, …)` in `dependencies.kt` builds the entire graph once. Every parameter has a production
  default, and the defaults are evaluated lazily in declaration order, so overriding an early one flows into the later
  ones: pass `httpClient = shopifyRewritingHttpClient(port)` and the `ShopifyGraphqlServiceFactory` and
  `ShopifyOAuthService` defaults pick it up.
- `Application.dssModule(deps)` in `dssModule.kt` is the one composition root: plugins, then every route family.
  `main` installs it into the CIO server and a request → response test installs it under `testApplication`, so the
  stack under test is the production stack (same auth guard, same error shaping, same trace ids).
- Lifecycle belongs to Ktor. `main` registers no shutdown hook of its own: `embeddedServer` does that, with the grace
  period set in its `configure` block, and the module subscribes `deps.close()` to `ApplicationStopped`, so the HTTP
  clients close both on SIGTERM and at the end of every `testApplication`. `DssDependencies` is `AutoCloseable`.
- Tests build the same graph with fakes substituted (`monolithService = FakeMonolithService()`, an
  `InMemoryShopTokenStore` with a known token) and then exercise `deps.shopifyWebhookHandlers` and friends, or the
  whole module, so the routing wired in production runs unchanged.
- The shop token has one owner, the `ShopTokenStore`: handlers ask the factory for a service and call `remember` on
  the store; nothing else reads or writes tokens.
- Every collaborator that talks to the outside world is an interface with one real and one fake implementation:
  `MonolithService` / `HttpMonolithService` / `FakeMonolithService`, `ShopifyGraphqlService` /
  `HttpShopifyGraphqlService` / `FakeShopifyGraphqlService`, `ShopifyGraphqlServiceFactory` /
  `HttpShopifyGraphqlServiceFactory` / `FakeShopifyGraphqlServiceFactory`. Adding an outside dependency means adding
  all three.
- A `ShopifyGraphqlService` is bound to one shop (its token is injected at construction). Handlers obtain one per
  request through `ShopifyGraphqlServiceFactory.forShop(shop)` and answer `DssError.MissingShopifyAdminToken` when
  that returns `null`.
- Workflows are top-level functions that take the services they need as parameters; they never see Ktor server types
  (`ArchitectureTest` checks), so they are testable with the in-memory fakes alone.


# Internal dependency policy
See `test/dropnext/dss/ArchitectureTest.kt` for a formal specification, using the Konsist library, of the rules
regarding what packages a package may import from: `domain` depends on nothing of ours, `lib` never on an
application package and its sub-packages never on each other (only on `lib/json`, `lib/crypto`, `lib/logging`),
`workflow` never on the HTTP or view layers, `presentation` on nothing but `domain`. Plus the other rules it keeps: no
reflection, no wildcard imports, no ad-hoc `Json {}` or `HttpClient(...)`, only `Config` reads the environment,
secrets redact and never serialize, Graphql-generated types only inside `lib/shopify/` and the listed translation
boundaries, no hand-written contract DTOs or `OutBoundMonolithPaths`, the file naming rule and the `test/` ↔ `src/`
mirroring rule. Add a rule there before introducing a new layer, and extend an allowlist only with a one-line comment
saying why.


# External dependency policy
We prefer not to add any library dependencies to the project.
If it is important that a dependency is added, that should be mentioned as soon as possible (during planning or speccing).
A human always has to accept that a dependency is added.
Browse the internet to ensure you suggest the latest stable version of the dependency (unless you have a good reason not to: state that reason clearly).
When suggesting Java or Kotlin libraries, investigate if they --or their transitive dependencies-- make use of JVM reflection: state your findings.


# Reflection policy
As guarded against in the `ArchitectureTest`: we do not use reflection in the project.
There are some exceptions, those can be found in the `ArchitectureTest` as well.


# Error handling
- **Expected failures are values, not exceptions.** Every outbound call returns a result4k `Result`:
  `ShopifyResult<T>` with a `ShopifyError` (`Network`, `GraphqlError`, `UserError`, `NotFound`) or
  `MonolithResult<T>` with a `MonolithError` (`Transport`, `Rejected`). The triage — transport exception, top-level
  Graphql errors, a payload's `userErrors`, a non-success status — happens once, inside `HttpShopifyGraphqlService`
  and `HttpMonolithService`; a caller pattern-matches on `Success` / `Failure` and never sees the wire. Workflows pass
  the same result up. Exceptions are for bugs.
- **Handlers answer through `DssError`** (`lib/ktor/DssError.kt`): a handler names the failure shape
  (`InvalidRequest`, `MissingShopifyAdminToken`, `UpstreamFailure`, …) and `respondError` / `respondTextError` pick
  the status and body — no scattered `call.respond(HttpStatusCode.BadRequest, …)`. `ShopifyError.toDssError()` in
  `handler/` is the one mapping from a failure to an answer. An escaped exception becomes `DssError.Internal`.
- **Request-shape failures are the framework's.** A body Ktor cannot decode is a `BadRequestException`, a body the
  domain validators reject (`installRequestValidation` runs them through Ktor's `RequestValidation` plugin) is a
  `RequestValidationException`; `installStatusPages` maps both to the `400` of `DssError.InvalidRequest`, so a handler
  calls `call.receive<T>()` and only ever sees a valid request. The values-not-exceptions rule is for domain and
  upstream failures, which happen after that point.
- **Generic error messages in production**: never leak internal state or an upstream response body to the caller;
  the details go to the log, under the request's `trace_id`.


# Logging
Every line goes to the console appender in `logback.xml`. Two things are switchable on top of it, and both are off
unless configured, which is how the monolith does it too.

- **Logflare shipping** (`LOGFLARE_SOURCE_NAME` + `LOGFLARE_API_KEY`, optional `LOGFLARE_ENDPOINT`). `attachLogflareAppender`
  in `app.kt` adds the appender after the environment is read — `logback.xml` is parsed long before the service knows
  its configuration, so it cannot carry these values. The appender ships the MDC with every event, so the request's
  `trace_id` arrives as a queryable field and a webhook can be followed across DSS and the monolith. The shipping half
  (`LogflareBatchSender`) holds a bounded queue and its own JDK HTTP client: a log shipper must never park the thread
  that logged, and must not depend on the clients being closed at shutdown. It is stopped last in the shutdown hook,
  which drains what is queued.
- **Per-request call logging** (`DSS_MODE=DEV`). Off in `PROD`, the default, as in the monolith, whose request and
  query logs exist in its `DEV` mode only; this is the same bargain under the same name. The plugin itself
  (`installCallLogging`) is always installed, because its `callIdMdc` is what puts the trace id in the MDC and wraps
  the request in `MDCContext`; the mode only mutes the line. The format is spelled out rather than left to Ktor's
  default, which logs the whole URI — see "Security".
- **The trace id** comes from Ktor's `CallId` plugin (`installCallId`): the caller's `X-Request-Id` or `X-Trace-Id`
  when present, 16 hex characters otherwise, echoed as `X-Trace-Id` on every response. The monolith client carries
  the client-side `CallId` plugin, which forwards it as `X-Trace-Id` from the coroutine context; nothing reads the
  MDC to do so.

Quieting a noisy library belongs in `logback.xml` next to the two that are already there.

## Logging conventions
Shared with the monolith, so a line from either service reads the same way in Logflare.

- **One logger per file**: `private val log = KotlinLogging.logger {}`, lambda-style calls (`log.info { … }`,
  `log.warn(e) { … }`), never a `LoggerFactory.getLogger` outside the Logflare wiring in `app.kt`.
- **`domain` does not log**: a domain function returns its answer, it does not narrate it (`ArchitectureTest` forbids
  the import). Workflows and handlers log; `lib` logs only its own failures.
- **Messages are prose with `key=value` detail**, e.g. `Webhook verified topic=orders/create shop=acme.myshopify.com`;
  the trace id is never interpolated, the MDC carries it.
- **Levels**: `error` for what needs a human (a bug, an upstream down), `warn` for a refused request or a failed
  optional step, `info` for one line per meaningful event, never per loop iteration. The root level is `INFO`
  everywhere; there is no `LOG_LEVEL` variable.
- **Nothing secret, nothing bulky**: no tokens, no `Authorization` headers, no request or response bodies, no query
  strings. Secrets are value classes that print `***`, so interpolating one by accident prints nothing useful.
- **Console pattern** `%d{HH:mm:ss.SSS} [%-5level] %logger{36} - trace_id=%X{trace_id} %msg%n`: the monolith's
  pattern plus the MDC key, no thread name (under coroutines it names a pool worker).


# Security
- **Inbound Shopify webhooks** are authenticated by the `X-Shopify-Hmac-Sha256` body signature
  (`ShopifyHmacVerifierService.verifyWebhook`, constant-time compare); the OAuth callback by its query-string HMAC
  plus our signed `state`.
- **Inbound monolith calls** are authenticated by `Authorization: Bearer <DSS_API_KEY>`, compared in constant time
  by the `bearer` provider behind `authenticate(MONOLITH_WEBHOOK_AUTH)` — a new monolith-facing route goes inside
  that block.
- **Outbound to the monolith** is HTTPS-only in production (`DSS_ALLOW_INSECURE_MONOLITH` is the dev escape hatch);
  `MONOLITH_API_KEY` travels in the `Authorization` header, never in a URL.
- **Nothing secret in the logs**: no Admin tokens (`shpat_…`), no `Authorization` headers, no request or response
  bodies. The HTTP clients deliberately do not install Ktor's `Logging` plugin — keep it that way. `installCallLogging`
  and the unhandled-exception line in `installStatusPages` log the path and never the URI, because the OAuth callback
  carries `code` and `hmac` in the query string.


# Operational boundary
A developer machine's `.env` can hold real Shopify Admin tokens (`DSS_SHOP_ACCESS_TOKENS`) and a staging or production
`MONOLITH_BASE_URL`. Anything that would act on a real shop or a remote monolith is **human-only**; Claude (and any
other automation) must not:

* start the service (`./gradlew run`, `docker compose up`, the container) or send requests to a running instance,
* trigger the OAuth install (`/install`), which registers webhooks on the shop,
* call `PUT /stores/api-key`, `POST /sync-shipments-with-fulfillments` or `POST /tracking-update` on a real shop,
* use `curl` (or any client) against a Shopify Admin API or a remote monolith with a real token.

This rule is categorical — it is not overridable per-turn. If the user asks for such a command to be run, print the
exact command for the human developer to execute manually, but **never** run it. The test suite is always safe: it
talks only to in-process fakes. `graphqlIntrospectSchema` is safe too: it reads Shopify's public schema proxy without
a token.


# Testing
All tests are in-process and fast: there is no database. Three flavours, cheapest first; pick the cheapest one that
exercises the behaviour:

1. **Pure unit** — mappers, parsers, validation, config (`ToProductVariantItemsTest`, `LegacyIdFromGidTest`, `ConfigTest`).
2. **Fake-backed** — workflows and handler classes against the in-memory fakes in `test/dropnext/dss/testutil/fake/`
   (`FakeMonolithService`, `FakeShopifyGraphqlService`, `FakeShopifyGraphqlServiceFactory`), wired through
   `dssDependencies(testConfig(), …)`.
3. **Wire-level** — the `Http*` implementations against a recording HTTP fake on a random port
   (`FakeMonolithHttpServer`, `FakeShopifyGraphqlServer` + `shopifyRewritingHttpClient`), and request → response
   tests of the whole `dssModule` under `testApplication`.

- **Fakes, never mocks**: no MockK/Mockito. A fake is a hand-written implementation of our own interface that records
  what it was asked and answers what the test configured.
- **Assert both** the HTTP status and the effect (the recorded outbound call, or the decoded response body) in
  request → response tests; a status alone proves little.
- **Never broaden an `assert` to make a test pass.** A well-designed failing test beats a weak passing one — leave it
  failing and tell me. Allowing more than one HTTP status in one assert is the tell-tale sign.
- Prefer Kotlin power-assert (`assert(b == a)`, `assert(substr in str)`) over JUnit's `assertEquals` /
  `assertNotNull` / `assertContains`; one fact per `assert`, so a failure names the fact.
- **Fixtures have names**: build inputs with `minimalOrder()`, `diagramCrossFoOrder()`, `testConfig()` and friends
  rather than inlining a generated-type literal in every test.
- **Every `MonolithService` and `ShopifyGraphqlService` method needs a wire-level test** whose case does not rely on
  an empty response (so the deserialization is exercised); `TestSuiteArchitectureTest` enforces it.
- **A request → response test goes through `withDssApp(deps)`**, which mounts the production `dssModule`. A test that
  spins up its own server proves only that its own wiring works.
- The suite's own conventions — how a fake records, what a test may own, one status per assert — are checked by
  `TestSuiteArchitectureTest`, the way `ArchitectureTest` checks `src/`.

The full rules — the fake catalogue, how to spin up the app in a test, `runBlocking` for suspend code, where tests
live and the `test/` ↔ `src/` mirroring rule — are in `.claude/rules/tests.md`.


# Code style
- **2 spaces indentation** (defined in `.editorconfig`)
- **Use `val` over `var`** wherever possible
- **Expression bodies** for short (one or two lines long) single-expression functions
- **Kotlin-style** over Java-style code
- **No `lateinit`** in production code; favour immutable `val` modelling
- **Generic error messages in production** never leak internal state to users (in development be more verbose)
- **2 returns between the import block and the first statement**
- **Prefer the kotlinesque `headerNames.forEach { headerName -> ... }` over `for (headerName in headerNames) { ... }`**
- Dedupe code where reasonable, or propose areas for deduplication when providing a summary of the changes made
- **Extract common variables early** and **return early** when possible
- Instead of fully qualified type references (e.g. `dropnext.dss.lib.shopify.ShopDomain`), use proper imports and the
  short form (e.g. `ShopDomain`) unless ambiguity needs to be avoided.


# Naming
General rules, everywhere:
- Pay attention to naming: a function returning a collection should have a plural name, a function returning a single
  item should have a singular name, etc.
- Prefer improving a name (to make it self-documenting) over adding a doc comment.
- **Graphql casing** in our own identifiers, log strings and documentation: `Graphql` (PascalCase, e.g.
  `GraphqlClientCache`), `Gql` (short PascalCase, e.g. `GqlClient`) or `gql` (camelCase / lowercase, e.g. `gqlClient`,
  `gql-schema`). **Never** `GraphQL` or `GraphQl`. Third-party types like
  `com.expediagroup.graphql.client.ktor.GraphQLKtorClient` keep their upstream casing in imports, but our variables
  and fields holding such instances follow the rule (e.g. `private val gqlClient: GraphQLKtorClient`).

Files and types:
- A file whose main declaration is a class or interface is `PascalCase.kt` after it (`ShopDomain.kt`); a file of
  top-level functions is `lowerCamel.kt` after its main function (`syncShopifyTrackingEvent.kt`,
  `oauthRoutes.kt`). The `test/` mirroring check in `ArchitectureTest` relies on this.
- **Handlers**: one `<Family>Handlers` class per route family in `handler/<Family>Handlers.kt`, with `handle<What>`
  methods (`handleShopifyWebhook`, `handleTrackingUpdate`).
- **Routing**: `routing/<family>Routes.kt` defines a single `Route.<family>Routes(handlers)`. Not `install…`: in
  Ktor that verb means installing a plugin, and `lib/ktor/install*.kt` is where those live.
- **Services**: `<Name>Service` is the interface, `Http<Name>Service` the real implementation and `Fake<Name>Service`
  (in `test/`) the fake; the same for `ShopifyGraphqlServiceFactory`.
- **Outcome types**: one call answers a result4k `Result` (`ShopifyResult<T>`, `MonolithResult<T>`); a summary of
  many is a `*Report` (`WebhookRegistrationReport`, `ShopInstallReport`); what a view renders is an `*Outcome`
  (`MonolithPersistOutcome`). Errors are the sealed `ShopifyError` and `MonolithError`, nothing else.
- **Secrets and ids** are value classes in `domain/` (`ShopifyAdminToken`, `DssApiKey`, `ShopifyOrderId`, …); a
  `String` token or a `Long` id only exists on the contract DTOs and is wrapped at the handler boundary.
- **Paths**: inbound constants live in `Paths`; outbound path objects are `OutBound<Target>…Paths`.
- **Tests**: `<SourceFile>Test.kt` in the mirrored package (see `.claude/rules/tests.md`).


# API-related DTO conventions
- **snake_case JSON keys**: All API-exposed DTOs (request and response bodies) use `snake_case` for their JSON field
  names. Kotlin properties remain `camelCase` — the mapping is done with `@SerialName("snake_case_name")` annotations
  on each multi-word property. Single-word properties (e.g. `status`, `carrier`) do not need `@SerialName`.
- **Separate blank lines between properties**: In API DTOs with `@SerialName` annotations, add a blank line between
  each property for readability.
- **DTOs on the DSS ↔ monolith contract are generated** into `dropnext.dss.contract`: change
  `src/resources/monolith-dss-openapi.json` (and the monolith that serves it), never hand-write a DTO or reuse a
  generated one as an internal model or vice-versa.


# Graphql queries
- One operation per file in `src/resources/<OperationName>.graphql`, the file named after the operation it declares;
  the `graphql-kotlin` plugin generates `dropnext.graphql.generated.<OperationName>` from it at compile time.
- Only `lib/shopify/` runs operations (the `ShopifyGraphqlService` methods, each answering a typed `ShopifyResult`);
  everything else programs against that interface. The files that must still see generated types (the mappers, the
  fulfillment matcher, the two workflows that plan mutations from an `Order`) are listed in
  `ArchitectureTest.graphqlGeneratedAllowList`.
- Bumping the Shopify API version touches several places that must agree; the procedure and the response-handling
  conventions are in `.claude/rules/graphql.md`.


# Implementation workflow for small changes
Do the work and make sure the tests still pass. That's it.


# Implementation workflow for non-trivial changes
Any change touching **new handlers, new or modified Graphql operations, new outbound monolith calls, new webhook topic
handling, the OAuth/install flow, or anything security-relevant** follows our Verified Spec-Driven Development (VSDD)
loop; skip it for typo fixes, comment updates, doc tweaks, or single-line config changes.

- **Phase 1 — Spec crystallization.** Articulate what changes, the behavioral contract, edge cases, and the reuse
  inventory; deliver small, sequential spec files under `/specs/<end-goal>/NNN-title.md` (numbered in tens). Human
  approval is required before coding.
- **Phase 2 — Test-first (Red → Green → Refactor).** Re-read the (possibly edited) specs, write failing tests first,
  then the minimal implementation, then refactor while green. Compile `main` cleanly after each code-changing step.
  Do **not** reference spec numbers/names in `.kt`, `.graphql` or spec-JSON comments — specs are ephemeral.
- **Phase 3 — Verification checks.** Review spec fidelity, test quality, security surface, architecture-test
  compliance, naming, and traceability; record findings in a `VERIFICATION.md` in the spec folder.
- **Phase 4 — Implement the verification fixes.** Loop back per issue (spec gap → Phase 1, weak test → Phase 2 Red,
  implementation issue → Phase 2 Refactor). Never patch forward or weaken assertions.

The full four-phase procedure — everything each phase must produce, the spec-file naming rules, and the loop-back
details — is in `.claude/rules/workflow.md`.


# Natural language instructions
Since code contains a lot of natural language text (mostly in comments, but also in the UI of the application), the project prefers:
* Full sentences: starting with a capital letter and ending with a dot (".")
* No capitalization of every major word in headings and titles (so "Code style" is preferred over "Code Style")
* Add particles (the, a, an, etc.) in sentences where they are supposed to be (IntelliJ's spell checker annoys me otherwise)


# Commenting
We appreciate terse comments that explain why the code they document was needed,
**especially** important when the "why" is not obvious to a human from just reading the code!

## Documentation comments (KDoc)
Should follow the same "why" + "terse" rules as other comments.
Doc comments that just restate the function name, return value, and parameters are utterly useless and forbidden!
Prefer to improve the name of the function or class (to make it self-documenting) over adding a doc comment.

If doc comments can fit on one line, they should be on one line! So:

    /** Short doc comment. */

Over:

    /**
     * Short doc comment
     */

In all cases: avoid comments that do not add value. For instance a function by the name `markFulfilled`
that consists of 3 simple statements should not have a KDoc comment saying "Marks as fulfilled" — that's just noise.

Generated code (everything under `build/generated/`) is never commented or edited: fix the `.graphql` file or the
OpenAPI spec instead.
