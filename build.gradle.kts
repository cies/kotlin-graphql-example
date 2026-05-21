import java.io.File
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql

description = "GraphQL client Kotlin with compile-time query validation and IDE integration"

plugins {
  application
  kotlin("jvm") version "2.2.20"
  id("org.jetbrains.kotlin.plugin.power-assert") version "2.2.20"
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.kotlinGraphql)
  id("org.openapi.generator") version "7.6.0"
}

buildscript {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation(libs.logbackClassic)
  implementation(libs.graphqlKotlinKtorClient)
  implementation(libs.graphqlKotlinClientSerialization)
  implementation(libs.ktorClientOkhttp)
  implementation(libs.ktorClientContentNegotiation)
  implementation(libs.ktorSerializationKotlinxJson)
  implementation(libs.ktorServerCio)
  implementation(libs.ktorServerStatusPages)
  implementation(libs.ktorServerContentNegotiation)
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

application {
  mainClass.set("dropnext.dss.ShopifyServerKt")
}

kotlin {
  // Target latest installed Java SDK.
  jvmToolchain(24)
}

graphql {
  client {
    packageName = "dropnext.graphql.generated"

    // endpoint = "https://beta.pokeapi.co/graphql/v1beta"
    endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2026-04"

    allowDeprecatedFields = true
    serializer = GraphQLSerializer.KOTLINX

    // Prevents "To prevent Denial Of Service attacks, parsing has been canceled" errors...
    parserOptions {
      maxCharacters = 25000000
      maxTokens = 250000
    }
  }
}

tasks.graphqlIntrospectSchema {
  outputFile = file("src/main/graphql-schema/schema.graphql")
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
  functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertNull")
}

private val openApiSpecFile: File =
  sequenceOf(
    layout.projectDirectory.file("src/main/resources/openapi.json").asFile,
    layout.projectDirectory.file("openapi.json").asFile,
  ).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
    ?: throw GradleException(
      """
      OpenAPI spec not found. Expected one of (non-empty):
        ${layout.projectDirectory.asFile.absolutePath}${File.separator}src${File.separator}main${File.separator}resources${File.separator}openapi.json
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
  modelPackage.set("dropnext.dss.lib.dss.dto")
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
