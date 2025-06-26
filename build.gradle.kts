import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql

description = "GraphQL client Kotlin with compile-time query validation and IDE integration"

plugins {
  application
  kotlin("jvm") version "2.2.0"
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
  implementation(libs.ktorClientOkhttp)
  implementation(libs.ktorClientLogging)
}

application {
  mainClass.set("com.example.KtorClientApplicationKt")
}

graphql {
  client {
    packageName = "com.example.graphql.generated"

    // endpoint = "https://beta.pokeapi.co/graphql/v1beta"
    endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2024-10"

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
