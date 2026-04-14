import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql

description = "GraphQL client Kotlin with compile-time query validation and IDE integration"

plugins {
  application
  kotlin("jvm") version "2.2.20"
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.kotlinGraphql)
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
  implementation(libs.graphqlKotlinKtorClient)
  implementation(libs.graphqlKotlinClientSerialization)
  implementation(libs.ktorClientOkhttp)
  implementation(libs.ktorClientLogging)
  implementation(libs.ktorClientContentNegotiation)
  implementation(libs.ktorSerializationKotlinxJson)
  implementation(libs.ktorServerCio)
  implementation(libs.ktorServerStatusPages)
  implementation(libs.ktorServerContentNegotiation)
  implementation(libs.ktorServerCallLogging)
}

application {
  mainClass.set("com.example.ShopifyServerKt")
}

kotlin {
  // Target latest installed Java SDK.
  jvmToolchain(24)
}

graphql {
  client {
    packageName = "com.example.graphql.generated"

    // endpoint = "https://beta.pokeapi.co/graphql/v1beta"
    endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2026-01"

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
