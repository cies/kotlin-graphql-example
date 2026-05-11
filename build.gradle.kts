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
  testImplementation(libs.konsist)
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
    packageName = "com.example.graphql.generated"

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

powerAssert {
  functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertEquals", "kotlin.test.assertNull")
}

openApiGenerate {
  generatorName.set("kotlin")
  inputSpec.set("$rootDir/docs/openapi/dss-api.yaml")
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
