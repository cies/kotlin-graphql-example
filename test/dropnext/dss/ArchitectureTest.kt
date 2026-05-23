package dropnext.dss

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test


/** Tests that enforce architecture rules relating to the dependencies that packages have on each other. */
class ArchitectureTest {

  @Test
  fun `the project's packages have correct dependencies on each other`() {
    // Our sources live in `src/` (not `src/main/kotlin/`), which defeats Konsist's
    // built-in production/test detection. Point Konsist at the directory explicitly instead.
    Konsist.scopeFromDirectory("src").assertArchitecture {
      // Define our "layers"
      val config = Layer("config", "dropnext.dss.config..")
      val shopify = Layer("shopify", "dropnext.dss.shopify..")
      val handler = Layer("handler", "dropnext.dss.handler..")
      val routing = Layer("routing", "dropnext.dss.routing..")
      val workflow = Layer("workflow", "dropnext.dss.workflow..")
      val presentation = Layer("presentation", "dropnext.dss.presentation..")
      val libFulfillment = Layer("lib/fulfillment", "dropnext.dss.lib.fulfillment..")
      val libMonolith = Layer("lib/monolith", "dropnext.dss.lib.monolith..")
      val libJson = Layer("lib/json", "dropnext.dss.lib.json..")
      val libKtor = Layer("lib/ktor", "dropnext.dss.lib.ktor..")
      val libAuth = Layer("lib/auth", "dropnext.dss.lib.auth..")

      // Define architecture assertions.
      // Note: `lib/dto` (generated OpenAPI DTOs) is intentionally not a layer here — virtually
      // every layer depends on it, so layer rules against `dropnext.dss.lib.dto..` would always
      // fail. Keeping it out is exactly what justifies splitting the old `lib/dss` package.
      config.doesNotDependOn(handler, routing, workflow, presentation, libFulfillment, libMonolith)
      // (allowed: config can depend on shopify for `normalizeShopDomain` — a shared helper)
      libMonolith.doesNotDependOn(handler, routing, workflow, presentation, libFulfillment, libAuth, shopify)
      libFulfillment.doesNotDependOn(handler, routing, workflow, presentation)
      // Generic infrastructure libs must not depend on any application layer.
      libJson.doesNotDependOn(handler, routing, workflow, presentation, libFulfillment, libMonolith, libAuth, shopify, config)
      // libKtor may depend on libAuth (e.g. `requireDssInternalSecret` uses `constantTimeEquals`).
      libKtor.doesNotDependOn(handler, routing, workflow, presentation, libFulfillment, libMonolith, shopify, config)
      // Auth helpers live above libMonolith (token fallback uses GetStore) and shopify (uses
      // shopifySubdomainShort). They never see HTTP / handlers / workflows directly.
      libAuth.doesNotDependOn(handler, routing, workflow, presentation, libFulfillment, libJson, libKtor, config)
      shopify.doesNotDependOn(handler, routing, workflow, presentation, libMonolith, libAuth)
      workflow.doesNotDependOn(handler, routing, presentation) // workflows must not depend on HTTP/view layers
      routing.doesNotDependOn(workflow, presentation)          // routing wires handlers, not views directly
      // presentation is a pure view layer: data in, HTML string out. No HTTP, no orchestration.
      presentation.doesNotDependOn(handler, routing, workflow, libFulfillment, libMonolith)
    }
  }

  /** Reflection-related imports that are always forbidden in production code. */
  private val reflectionImports = listOf(
    "java.lang.reflect.",
    "java.lang.Class",
  )

  /**
   * `kotlin.reflect` imports allowed because they are used by Kotlin's property-delegate protocol
   * and for compile-time property-name access (`.name`), not for runtime introspection.
   */
  private val allowedKotlinReflectImports = listOf(
    "kotlin.reflect.KProperty",
    "kotlin.reflect.KProperty0",
    "kotlin.reflect.KProperty1",
    "kotlin.reflect.KProperty2",
  )

  /**
   * Source patterns that indicate actual runtime reflection (as opposed to just using `KProperty`
   * for delegate signatures or `.name` access).
   */
  private val reflectionUsagePatterns = listOf(
    Regex("""\.\s*get\s*\("""),          // KProperty.get(instance)
    Regex("""\.\s*set\s*\("""),          // KMutableProperty.set(instance, value)
    Regex("""\.\s*call\s*\("""),         // KCallable.call(...)
    Regex("""\.\s*callBy\s*\("""),       // KCallable.callBy(...)
    Regex("""::class\s*\.\s*members"""),
    Regex("""::class\s*\.\s*memberProperties"""),
    Regex("""::class\s*\.\s*memberFunctions"""),
    Regex("""::class\s*\.\s*declaredMembers"""),
    Regex("""::class\s*\.\s*declaredMemberProperties"""),
    Regex("""::class\s*\.\s*declaredMemberFunctions"""),
  )

  /**
   * Files that are allowed to use reflection because they integrate with libraries that require it
   * (none currently — keep this list empty unless an unavoidable case appears).
   */
  private val reflectionAllowList = listOf<String>()

  @Test
  fun `forbid use of JVM reflection`() {
    Konsist.scopeFromDirectory("src")
      .files
      .filterNot { file -> reflectionAllowList.any { allowed -> allowed in file.path } }
      .assertFalse { file ->
        val importNames = file.imports.map { it.name }

        val forbiddenImports = importNames
          .filter { importName -> reflectionImports.any { importName.startsWith(it) } }

        val forbiddenKotlinReflectImports = importNames
          .filter { it.startsWith("kotlin.reflect.") }
          .filter { importName -> allowedKotlinReflectImports.none { allowed -> importName == allowed } }

        val hasAllowedKotlinReflectImport = importNames.any { importName ->
          allowedKotlinReflectImports.any { allowed -> importName == allowed }
        }
        val hasReflectionUsage = hasAllowedKotlinReflectImport &&
          reflectionUsagePatterns.any { it.containsMatchIn(file.text) }

        val allOffending = forbiddenImports + forbiddenKotlinReflectImports
        if (allOffending.isNotEmpty()) {
          println(
            "ERROR: File ${file.path} uses reflection imports: $allOffending. " +
              "Avoid JVM reflection. If unavoidable, add the file to reflectionAllowList in ArchitectureTest."
          )
        }
        if (hasReflectionUsage) {
          println(
            "ERROR: File ${file.path} imports allowed KProperty types but uses them for runtime reflection. " +
              "Only use KProperty for delegate signatures and .name access."
          )
        }
        allOffending.isNotEmpty() || hasReflectionUsage
      }
  }

  /**
   * Ktor server packages imply request/response handling and thus should not appear in workflows.
   * Outbound `io.ktor.client.*` usage is allowed — workflows may make HTTP calls, they just must
   * not handle inbound requests.
   */
  private val forbiddenKtorPrefixesInWorkflows = listOf(
    "io.ktor.server.",
    "io.ktor.http.",
  )

  @Test
  fun `workflow package must not depend on Ktor server or HTTP request types`() {
    Konsist.scopeFromDirectory("src")
      .files
      .filter { "/dropnext/dss/workflow/" in it.path }
      .assertFalse { file ->
        val httpImports = file.imports
          .map { it.name }
          .filter { importName -> forbiddenKtorPrefixesInWorkflows.any { importName.startsWith(it) } }
        if (httpImports.isNotEmpty()) {
          println(
            "ERROR: Workflow file ${file.path} imports Ktor server / HTTP types: $httpImports. " +
              "Workflows must not handle HTTP requests; pass extracted values instead."
          )
        }
        httpImports.isNotEmpty()
      }
  }

  @Test
  fun `presentation layer does not know about Ktor server or HTTP client`() {
    // The view should take data in and return a String. It must not see ApplicationCall,
    // HttpClient, or any other transport-layer type, so it can be tested in isolation.
    val forbiddenPrefixes = listOf("io.ktor.server.", "io.ktor.client.", "io.ktor.http.")
    val violations = java.io.File("src/dropnext/dss/presentation").walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .flatMap { file ->
        file.readLines()
          .withIndex()
          .filter { (_, line) ->
            val trimmed = line.trim()
            trimmed.startsWith("import ") && forbiddenPrefixes.any { trimmed.contains(it) }
          }
          .map { (idx, line) -> "${file.path}:${idx + 1}  ${line.trim()}" }
      }
      .toList()
    assert(violations.isEmpty()) {
      "presentation/* must not depend on Ktor server/client/http — keep it a pure view layer:\n" +
        violations.joinToString("\n")
    }
  }

  /**
   * Files allowed to construct a `kotlinx.serialization.Json {}` instance. Everywhere else must
   * reuse the shared `AppJson` (inbound Shopify) or `MonolithJson` (outbound monolith) singletons
   * so serialization config stays consistent.
   */
  private val jsonConstructionAllowList = listOf(
    "/dropnext/dss/lib/json/",
  )

  @Test
  fun `forbid ad-hoc Json instance construction outside lib_json`() {
    val jsonConstructor = Regex("""\bJson\s*\{""")
    Konsist.scopeFromDirectory("src")
      .files
      .filterNot { file -> jsonConstructionAllowList.any { allowed -> allowed in file.path } }
      .assertFalse { file ->
        val hasJsonImport = file.imports.any { it.name == "kotlinx.serialization.json.Json" }
        val constructs = hasJsonImport && jsonConstructor.containsMatchIn(file.text)
        if (constructs) {
          println(
            "ERROR: File ${file.path} constructs its own `Json { ... }` instance. " +
              "Reuse AppJson or MonolithJson from dropnext.dss.lib.json instead, " +
              "or add a new shared singleton there if a different config is genuinely needed."
          )
        }
        constructs
      }
  }

  /**
   * Files allowed to construct a Ktor `HttpClient(...)`. Everywhere else must receive an
   * `HttpClient` via dependency injection so engine config and OkHttp pooling stay consistent.
   */
  private val httpClientConstructionAllowList = listOf(
    "/dropnext/dss/lib/ktor/httpClientBuilders.kt",
  )

  @Test
  fun `forbid ad-hoc HttpClient construction outside SharedHttpClient`() {
    // Match `HttpClient(` as a constructor call. `HttpClient` as a type reference (e.g. parameter
    // type) lacks the trailing `(`, so the pattern is precise without needing imports.
    val httpClientConstructor = Regex("""\bHttpClient\s*\(""")
    Konsist.scopeFromDirectory("src")
      .files
      .filterNot { file -> httpClientConstructionAllowList.any { allowed -> allowed in file.path } }
      .assertFalse { file ->
        val constructs = httpClientConstructor.containsMatchIn(file.text)
        if (constructs) {
          println(
            "ERROR: File ${file.path} constructs its own `HttpClient(...)`. " +
              "Inject the shared client created by `createSharedHttpClient()` instead."
          )
        }
        constructs
      }
  }

  @Test
  fun `main sources do not use wildcard imports`() {
    // Konsist's KoImport.name strips the trailing `.*`, so a Konsist-based check silently passes.
    // We grep the source files directly — no extra dependency, no false negatives.
    val wildcardImportLine = Regex("""^\s*import\s+[\w.]+\.\*\s*$""")
    val violations = java.io.File("src").walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .flatMap { file ->
        file.readLines()
          .withIndex()
          .filter { (_, line) -> wildcardImportLine.matches(line) }
          .map { (idx, line) -> "${file.path}:${idx + 1}  ${line.trim()}" }
      }
      .toList()
    assert(violations.isEmpty()) {
      "Wildcard imports are forbidden in production sources — use explicit imports:\n" +
        violations.joinToString("\n")
    }
  }

  @Test
  fun `no hand-written Kotlin under lib_dto`() {
    // DTOs in dropnext.dss.lib.dto are generated by openApiGenerate from openapi.json.
    // Hand-written copies would drift from the spec — find them by looking for any .kt file
    // under src/dropnext/dss/lib/dto/ (sources tree only; generated DTOs live under build/).
    val handWritten = Konsist.scopeFromDirectory("src")
      .files
      .filter { "/dropnext/dss/lib/dto/" in it.path }
    assert(handWritten.isEmpty()) {
      "Hand-written DTOs belong in openapi.json codegen only: ${handWritten.map { it.path }}"
    }
  }

  /**
   * Test files exempt from the mirror-source rule. These tests legitimately don't map 1:1 to a
   * single source file (cross-cutting integration tests, meta-tests). Add entries sparingly and
   * always with a comment explaining why the exemption is necessary.
   */
  private val mirrorSourceAllowList = listOf(
    // Meta-test: enforces project-wide architecture rules, no single src counterpart.
    "/test/dropnext/dss/ArchitectureTest.kt",
    // Integration-style test exercising the webhook → monolith path end-to-end. Unit-level
    // coverage of MonolithOrderSync.kt would live in a separate MonolithOrderSyncTest.kt.
    "/test/dropnext/dss/workflow/WebhookMonolithSyncTest.kt",
    // Webhook-flavored variant of ShopDomainsTest — covers shop-domain handling on the inbound
    // webhook path specifically, with no single matching shopify/ source file.
    "/test/dropnext/dss/shopify/ShopDomainsWebhookTest.kt",
  )

  /**
   * Test-file basenames (no `.kt`) that are test infrastructure rather than tests of a src file.
   * Anything not ending in `Test` is already excluded; this list catches the rare cases that do.
   */
  private val testInfrastructureSuffixes = listOf("Fixtures", "Fake", "Server", "Configs", "Rewriter")

  @Test
  fun `every test file has a matching source file in the mirrored package`() {
    // Inverted from "every src needs a test": instead, every `<Name>Test.kt` must correspond to
    // a `<Name>.kt` (or lowerCamel `<name>.kt` for files of top-level functions) in the mirrored
    // src package. Catches stale test files after a rename and enforces a strict 1:1 naming
    // convention; deliberately does NOT enforce coverage (code review handles that).
    val srcFilesByPath: Set<String> = Konsist.scopeFromDirectory("src")
      .files
      .map { it.path }
      .toSet()

    val orphaned = Konsist.scopeFromDirectory("test")
      .files
      .filter { it.name.endsWith("Test") }
      .filterNot { file -> testInfrastructureSuffixes.any { file.name.endsWith(it) } }
      .filterNot { file -> mirrorSourceAllowList.any { it in file.path } }
      .mapNotNull { file ->
        val stem = file.name.removeSuffix("Test")
        val mirroredDir = file.path.substringBeforeLast("/").replace("/test/", "/src/")
        val pascalCandidate = "$mirroredDir/$stem.kt"
        val lowerCamelCandidate =
          "$mirroredDir/${stem.replaceFirstChar { it.lowercaseChar() }}.kt"
        if (pascalCandidate in srcFilesByPath || lowerCamelCandidate in srcFilesByPath) null
        else "${file.path}  (expected $pascalCandidate)"
      }

    assert(orphaned.isEmpty()) {
      "The following test files have no matching source file in the mirrored package. " +
        "Either rename the test to match, rename the source file, or add the test to " +
        "mirrorSourceAllowList with a comment explaining why:\n" +
        orphaned.joinToString("\n") { "  - $it" }
    }
  }
}
