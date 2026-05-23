import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion.VERSION_25
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql


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
      allWarningsAsErrors = true // better be strict; use suppressions to get rid of legitimate warnings
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
          exclude("dropnext/dss/lib/dto/**")
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
  sequenceOf(
    layout.projectDirectory.file("src/resources/openapi.json").asFile,
    layout.projectDirectory.file("openapi.json").asFile,
  ).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
    ?: throw GradleException(
      """
      OpenAPI spec not found. Expected one of (non-empty):
        ${layout.projectDirectory.asFile.absolutePath}${File.separator}src${File.separator}resources${File.separator}openapi.json
        ${layout.projectDirectory.asFile.absolutePath}${File.separator}openapi.json
      Docker: COPY openapi.json alongside Gradle configs and/or COPY src before running Gradle.
      """.trimIndent(),
    )

openApiGenerate {
  generatorName.set("kotlin")
  // file: URI — required on Windows when validateSpec is enabled (absolute paths break $ref resolution).
  inputSpec.set(openApiSpecFile.toURI().toString())
  skipValidateSpec.set(false)
  outputDir.set("${layout.buildDirectory.get()}/generated/openapi")
  modelPackage.set("dropnext.dss.lib.dto")
  generateApiTests.set(false)
  generateModelTests.set(false)
  globalProperties.set(mapOf(
    "models" to "",
    "apis" to "false",
    "supportingFiles" to "false",
  ))
  configOptions.set(mapOf(
    "library" to "jvm-ktor",
    "serializationLibrary" to "kotlinx_serialization",
    "dateLibrary" to "string",
    "enumPropertyNaming" to "UPPERCASE",
  ))
}

sourceSets["main"].kotlin.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")

tasks.named("compileKotlin") {
  dependsOn(tasks.named("openApiGenerate"))
}
