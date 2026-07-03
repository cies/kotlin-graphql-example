import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql
import groovy.json.JsonSlurper
import org.gradle.api.JavaVersion.VERSION_25
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile


plugins {
  alias(libs.plugins.kotlinJvm)

  // Running this compiler plugin prevents the need for using the JVM's reflection API (directly, or through `kotlin-reflect`).
  alias(libs.plugins.kotlinXSerialization)

  // Improves the DX on `assert` errors significantly (shows what went wrong).
  alias(libs.plugins.kotlinPowerAssert)

  // Generates typed Kotlin classes from the Shopify Admin Graphql schema and our `.graphql` query files.
  alias(libs.plugins.kotlinGraphql)

  // Generates DTOs from `openapi.json` so request/response shapes stay in sync with the monolith spec.
  alias(libs.plugins.openapiGenerator)

  // Code coverage reports; JaCoCo's Java agent instruments bytecode at test runtime only (no production deps).
  // Run `./gradlew jacocoTestReport`; output lands in `build/reports/jacoco/test/`.
  jacoco

  application
}

buildscript {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(25))
  }
}

application {
  mainClass.set("dropnext.dss.AppKt")
}

repositories {
  mavenCentral()
}

// Got tired of the src/main/kotlin/... path prefix.
// Since this is not a library, we don't care for the Maven convention.
sourceSets {
  main {
    kotlin {
      srcDir("src")
    }
    resources.srcDirs("src/resources", layout.buildDirectory.dir("generated/version"))
  }
  test {
    kotlin {
      srcDir("test")
    }
  }
}

// Generates `version.txt` on the runtime classpath from git metadata.
// Format: `<commit-date>-<short-sha>[-dirty]`. The `VERSION_TAG` env var (Dockerfile build arg, CI) can override this at runtime.
val generateVersionResource by tasks.registering {
  group = "build"
  description = "Generates version.txt on the runtime classpath from git metadata."

  val outputFile = layout.buildDirectory.file("generated/version/version.txt")
  outputs.file(outputFile)
  outputs.upToDateWhen { false } // git state can change without any file in `inputs` changing

  val projectRoot = layout.projectDirectory.asFile

  doLast {
    fun runGit(vararg args: String): String? = runCatching {
      val process = ProcessBuilder(listOf("git") + args)
        .directory(projectRoot)
        .redirectErrorStream(false)
        .start()
      val output = process.inputStream.bufferedReader().readText().trim()
      if (process.waitFor() == 0) output else null
    }.getOrNull()

    val sha = runGit("rev-parse", "--short", "HEAD")
    val date = runGit("log", "-1", "--format=%cs") // commit date in YYYY-MM-DD
    val dirty = runGit("status", "--porcelain")?.isNotBlank() == true

    val version = if (sha != null && date != null) {
      "$date-$sha" + if (dirty) "-dirty" else ""
    } else {
      "unknown"
    }

    val target = outputFile.get().asFile
    target.parentFile.mkdirs()
    target.writeText(version)
    logger.lifecycle("Generated version: $version")
  }
}

tasks.named("processResources") { dependsOn(generateVersionResource) }

tasks {
  withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      // Better be strict; use suppressions to get rid of legitimate warnings.
      allWarningsAsErrors = true

      jvmTarget.set(JVM_25)
      freeCompilerArgs.addAll(
        "-opt-in=kotlin.uuid.ExperimentalUuidApi", // more specific than "-opt-in=kotlin.RequiresOptIn"
        "-opt-in=kotlin.time.ExperimentalTime",    // is very stable and works well with kotlinx.serialization
        "-Xbackend-threads=8",                     // Use multiple compiler threads
      )
    }
  }

  withType<Test> {
    useJUnitPlatform()

    jvmArgs(
      "-XX:MaxMetaspaceSize=512m",
      "-Xms512m",
      "-Xmx2g",
    )
  }

  java {
    sourceCompatibility = VERSION_25
    targetCompatibility = VERSION_25
  }
}

dependencies {
  // Shopify Admin API Graphql client (typed Kotlin classes generated from `.graphql` queries).
  implementation(libs.graphqlKotlinKtorClient)
  implementation(libs.graphqlKotlinClientSerialization)

  // Ktor (HTTP server + client).
  implementation(libs.ktorClientOkhttp)
  implementation(libs.ktorClientContentNegotiation)
  implementation(libs.ktorSerializationKotlinxJson)
  implementation(libs.ktorServerCio)
  implementation(libs.ktorServerStatusPages)
  implementation(libs.ktorServerContentNegotiation)
  implementation(libs.ktorServerCallLogging)
  implementation(libs.ktorServerAuth)

  // HTML rendering for the OAuth install success page (no reflection).
  implementation(libs.kotlinxHtml)

  // Logging
  implementation(libs.kotlinLogging) // Kotlinesque wrapper over SLF4J
  implementation(libs.slf4jApi)      // facade API (also used directly for MDC)
  implementation(libs.logbackClassic) // Logback backend (configured via src/resources/logback.xml)

  // Test dependencies (see also the `power-assert` plugin definition, makes errors more actionable)
  testImplementation(kotlin("test-junit5")) // Umbrella package that pulls in lots of other testing libs
  testRuntimeOnly(libs.junitJupiterEngine) // Needed separately when using JUnit5
  testImplementation(libs.konsist) // For architecture tests, among other features
  testImplementation(libs.ktorServerTestHost) // In-memory Ktor test engine (`testApplication { … }`)
}

// Reproducible builds: every configuration's resolved dependencies get locked to `gradle/dependency-locks/`.
// Refresh locks with `./gradlew dependencies --write-locks`.
dependencyLocking {
  lockAllConfigurations()
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
  functions.addAll(listOf("kotlin.assert", "kotlin.require", "kotlin.check"))
}

jacoco {
  toolVersion = "0.8.14"
}

tasks.test {
  // One fork at a time — avoids OOM when many integration tests spin up Ktor/HTTP fakes.
  maxParallelForks = 1
  jvmArgs("-Xmx768m", "-XX:MaxMetaspaceSize=256m")
}

val monolithServiceGeneratedDtoPath = "dropnext/dss/lib/monolith/dto/generated"
val monolithPathsGeneratedDir = layout.buildDirectory.dir("generated/monolith-paths")
val monolithPathsGeneratedFile =
  layout.buildDirectory.file("generated/monolith-paths/dropnext/dss/lib/monolith/OutBoundMonolithPaths.kt")

tasks.named<JacocoReport>("jacocoTestReport") {
  // So `./gradlew jacocoTestReport` runs the tests too; otherwise it would silently report on stale exec data.
  dependsOn(tasks.named("test"))
  reports {
    html.required.set(true)
    xml.required.set(true) // Useful for any future CI integrations (Codecov, SonarQube, etc.).
    csv.required.set(false)
  }
  classDirectories.setFrom(
    files(
      classDirectories.files.map { dir ->
        fileTree(dir) {
          // Exclude generated OpenAPI DTOs (not authored by us).
          exclude("$monolithServiceGeneratedDtoPath/**")
        }
      }
    )
  )
}

graphql {
  client {
    packageName = "dropnext.graphql.generated"

    // endpoint = "https://beta.pokeapi.co/graphql/v1beta"
    endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2026-04"

    allowDeprecatedFields = true
    serializer = GraphQLSerializer.KOTLINX

    // Prevents "To prevent Denial Of Service attacks, parsing has been canceled" errors on Shopify's huge schema.
    parserOptions {
      maxCharacters = 25000000
      maxTokens = 250000
    }
  }
}

tasks.graphqlIntrospectSchema {
  outputFile = file("src/graphql-schema/schema.graphql")
}

tasks.graphqlGenerateClient {
  // The plugin defaults to `src/main/resources`; we moved resources to `src/resources`.
  queryFileDirectory.set(layout.projectDirectory.dir("src/resources"))
}

private val openApiSpecFile: File =
  layout.projectDirectory.file("src/resources/monolith-dss-openapi.json").asFile
    .let { if (it.isFile && it.canRead() && it.length() > 0L) it else null }
    ?: throw GradleException(
      """
        OpenAPI spec not found. Expected (non-empty):
          ${layout.projectDirectory.asFile.absolutePath}${File.separator}src${File.separator}resources${File.separator}monolith-dss-openapi.json
        Docker: COPY src/resources/monolith-dss-openapi.json before running Gradle (see Dockerfile).
      """.trimIndent(),
    )

// Path of the spec after `rewriteOpenApiSpecToV31` has migrated 3.0 nullability syntax
// to 3.1 union types. `openApiGenerate` reads from this rewritten file, not the source.
private val openApiSpecRewrittenFile: File =
  layout.buildDirectory.file("generated/openapi-spec/monolith-dss-openapi.json").get().asFile

// Why this task exists:
//   The shared monolith↔DSS spec is authored as OpenAPI 3.0-style: nullable string fields use
//   `{"type":"string","nullable":true}`. The spec's `openapi` version was bumped to `3.1.0` so
//   the generator would accept the (3.1-only) `webhooks:` block. But OpenAPI 3.1 *dropped* the
//   `nullable` keyword: in 3.1 you express nullability via a union type `{"type":["string","null"]}`.
//   The openapi-generator's 3.1 codepath silently ignores stale `"nullable": true` flags, which
//   regressed ~16 string fields across DTOs like `ShippingAddress` from nullable to non-null —
//   a binary-incompatible contract change.
//
// What this does:
//   Reads the source spec, rewrites every `{"type":"<scalar>","nullable":true}` into
//   `{"type":["<scalar>","null"]}`, and writes the result under `build/generated/openapi-spec/`.
//   `openApiGenerate` is then pointed at the rewritten file.
//
// When this can be removed:
//   - The source spec adopts 3.1 union-type nullability throughout (i.e. the monolith side emits
//     `["string","null"]` directly); OR
//   - The spec no longer needs the 3.1-only `webhooks:` block (revert `openapi` to `3.0.0`, after
//     which `"nullable": true` is valid again and the generator's 3.0 path handles it natively).
val rewriteOpenApiSpecToV31 by tasks.registering {
  group = "build"
  description = "Migrates OpenAPI 3.0-style `nullable: true` flags to 3.1 union-type nullability."

  // Captured into local vals so the doLast closure does not retain a reference to the Gradle
  // script object (which the configuration cache cannot serialize).
  val source = openApiSpecFile
  val target = openApiSpecRewrittenFile

  inputs.file(source)
  outputs.file(target)

  doLast {
    // Matches `"type":"<scalar>","nullable":true` — note: order-sensitive. The shared spec is
    // emitted by a single generator on the monolith side, so the field order is stable; if that
    // ever changes, broaden the regex (or move to JSON-tree rewriting via the OpenAPI parser).
    val nullableRewrite = Regex("""\"type\":\"([a-zA-Z]+)\",\"nullable\":true""")
    val rewritten = nullableRewrite.replace(source.readText()) { match ->
      "\"type\":[\"${match.groupValues[1]}\",\"null\"]"
    }
    target.parentFile.mkdirs()
    target.writeText(rewritten)
  }
}

// Generates `OutBoundMonolithPaths` from the same monolith OpenAPI spec as DTO codegen (`apis=false`
// skips path constants in openApiGenerate). Reads the source spec directly — paths/servers are
// unaffected by `rewriteOpenApiSpecToV31`.
val generateOutBoundMonolithPaths by tasks.registering {
  group = "build"
  description = "Generates OutBoundMonolithPaths.kt from monolith-dss-openapi.json."

  val source = openApiSpecFile
  val outputFile = monolithPathsGeneratedFile

  inputs.file(source)
  outputs.file(outputFile)

  doLast {
    @Suppress("UNCHECKED_CAST")
    val spec = JsonSlurper().parseText(source.readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val paths = spec["paths"] as? Map<String, Map<String, Any?>>
      ?: throw GradleException("OpenAPI spec missing non-empty 'paths' object")

    @Suppress("UNCHECKED_CAST")
    val servers = spec["servers"] as? List<Map<String, Any?>>
    val apiPathPrefix = servers
      ?.firstOrNull()
      ?.get("url")
      ?.toString()
      ?.trim()
      ?.trimEnd('/')
      ?.takeIf { it.isNotEmpty() }

    fun segmentToConstPart(segment: String, isFirstSegment: Boolean): String =
      segment.split('-')
        .filter { it.isNotEmpty() }
        .mapIndexed { wordIndex, word ->
          val lower = word.lowercase()
          if (isFirstSegment && wordIndex == 0) lower
          else lower.replaceFirstChar(Char::uppercaseChar)
        }
        .joinToString("")

    fun pathToConstName(path: String): String =
      path.trim('/')
        .split('/')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, segment -> segmentToConstPart(segment, isFirstSegment = index == 0) }
        .joinToString("")

    val httpMethodOrder = listOf("delete", "get", "patch", "post", "put")

    val pathEntries = paths.keys.sorted().map { path ->
      val operations = paths[path].orEmpty()
      val kdocLines = operations.keys
        .filter { it in httpMethodOrder }
        .sortedBy { httpMethodOrder.indexOf(it) }
        .map { method ->
          @Suppress("UNCHECKED_CAST")
          val op = operations[method] as? Map<String, Any?>
          val summary = op?.get("summary")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
          val summarySuffix = summary?.let { " — $it" }.orEmpty()
          "   * `${method.uppercase()} $path`$summarySuffix"
        }
      Triple(pathToConstName(path), path, kdocLines)
    }

    val duplicateNames = pathEntries.groupBy { it.first }.filter { it.value.size > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
      throw GradleException(
        "OpenAPI paths map to duplicate OutBoundMonolithPaths constant names: $duplicateNames",
      )
    }

    val body = buildString {
      appendLine("package dropnext.dss.lib.monolith")
      appendLine()
      appendLine("/**")
      appendLine(" * Outbound paths the DSS calls on the DropNext monolith. Appended to")
      appendLine(" * `Config.monolithBaseUrl` (+ optional `Config.monolithApiPrefix`) by `HttpMonolithService`.")
      appendLine(" *")
      appendLine(" * **Generated** from `src/resources/monolith-dss-openapi.json` by the")
      appendLine(" * `generateOutBoundMonolithPaths` Gradle task — do not edit by hand.")
      appendLine(" */")
      appendLine("@Suppress(\"ConstPropertyName\") // Less shouty field names.")
      appendLine("object OutBoundMonolithPaths {")

      if (apiPathPrefix != null) {
        appendLine("  /** OpenAPI `servers[0].url` — typical value for `MONOLITH_API_PREFIX`. */")
        appendLine("  const val apiPathPrefix = \"$apiPathPrefix\"")
        appendLine()
      }

      pathEntries.forEach { (constName, path, kdocLines) ->
        appendLine("  /**")
        kdocLines.forEach { appendLine(it) }
        appendLine("   */")
        appendLine("  const val $constName = \"$path\"")
        appendLine()
      }

      appendLine("}")
    }

    val target = outputFile.get().asFile
    target.parentFile.mkdirs()
    target.writeText(body.trimEnd() + "\n")
  }
}

openApiGenerate {
  generatorName.set("kotlin")
  // file: URI — required on Windows when validateSpec is enabled (absolute paths break $ref resolution).
  inputSpec.set(openApiSpecRewrittenFile.toURI().toString())
  skipValidateSpec.set(false)
  outputDir.set("${layout.buildDirectory.get()}/generated/openapi")
  modelPackage.set(monolithServiceGeneratedDtoPath.replace('/', '.'))
  generateApiTests.set(false)
  generateModelTests.set(false)
  globalProperties.set(
    mapOf(
      "models" to "",
      "apis" to "false",
      "supportingFiles" to "false",
    )
  )
  configOptions.set(
    mapOf(
      "library" to "jvm-ktor",
      "serializationLibrary" to "kotlinx_serialization",
      "dateLibrary" to "string",
      "enumPropertyNaming" to "UPPERCASE",
    )
  )
}

sourceSets["main"].kotlin.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")
sourceSets["main"].kotlin.srcDir(monolithPathsGeneratedDir)

tasks.named("openApiGenerate") {
  dependsOn(rewriteOpenApiSpecToV31)
}

tasks.named("compileKotlin") {
  dependsOn(tasks.named("openApiGenerate"), tasks.named("generateOutBoundMonolithPaths"))
}
