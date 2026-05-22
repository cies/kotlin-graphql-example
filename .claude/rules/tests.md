---
paths:
  - "test/dropnext/**/*.kt"
---
# Rules for test/dropnext/

## Test organization

- Test files mirror source file structure: `src/dropnext/dss/lib/dss/MonolithOrderMapper.kt` → `test/dropnext/dss/lib/dss/MonolithOrderMapperTest.kt`.
- Test class names = source file name (PascalCase) + `Test`.
- Helpers and fixtures live alongside the tests that use them (e.g. `OrderTestFixtures.kt`, `testing/fake/FakeMonolithService.kt`).


## Use fakes, not mocks

This service has no test base classes — tests are pure unit tests. Where a test needs to substitute an external dependency, prefer a **recording fake** over a mock or stub.

- `FakeMonolithService` (in `test/dropnext/dss/testing/fake/`) is the reference: it implements `MonolithService` and records every call so tests can assert on the recorded `CreateShopifyOrderRequest`.
- See [docs/TESTING_WITH_FAKE_SERVICES.md](../../docs/TESTING_WITH_FAKE_SERVICES.md).
- Never make real HTTP calls from tests — neither to Shopify nor to the monolith.


## Assertion patterns

### Use Kotlin Power-Assert (preferred)

```kotlin
// CORRECT — power-assert
assert(response.status == OK)
assert(orderId != null)
assert("success" in responseBody)
assert(orders.size == 3)
assert(runCatching { risky() }.exceptionOrNull() is IllegalStateException)

// WRONG — JUnit assertions
assertEquals(OK, response.status)        // Don't use
assertNotNull(orderId)                   // Don't use
assertContains(responseBody, "success")  // Don't use
```

### Conversion guide

- `assertEquals(a, b)` → `assert(b == a)` (reversed order!)
- `assertTrue(x)` → `assert(x)`
- `assertFalse(x)` → `assert(!x)`
- `assertContains(str, substr)` → `assert(substr in str)`
- `assertNull(x)` → `assert(x == null)`
- `assertNotNull(x)` → `assert(x != null)`
- `assertThrows<T> { }` → `assert(runCatching { }.exceptionOrNull() is T)`

### Always test both

When checking HTTP responses, assert both the status AND a piece of response content:

```kotlin
val response = handler(request)
assert(response.status == OK)
assert("Order accepted" in response.bodyString())
```


## Quality over passing tests

- **NEVER** broaden assertions to make a test pass. Allowing multiple HTTP statuses in a single assertion is a red flag.
- A failing well-designed test is more valuable than a passing meaningless one. If you don't know how to fix a well-designed test, leave it failing with a short comment explaining why.


## Architecture tests

`ArchitectureTest` (Konsist) enforces package-layer dependencies and reflection bans. When adding a new top-level package or relaxing a rule, update the test in the same change.
