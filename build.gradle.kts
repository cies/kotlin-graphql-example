import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.graphql

description = "GraphQL client Kotlin with compile-time query validation and IDE integration"

plugins {
    application
    kotlin("jvm") version "2.2.0"
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.graphql)
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
    implementation(libs.graphql.kotlin.ktor.client)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
}

application {
    mainClass.set("com.example.KtorClientApplicationKt")
}

graphql {
    client {
        packageName = "com.expediagroup.graphql.generated"

        endpoint = "https://beta.pokeapi.co/graphql/v1beta"
        // endpoint = "https://shopify.dev/admin-graphql-direct-proxy/2024-10"

        allowDeprecatedFields = true
        serializer = GraphQLSerializer.KOTLINX
    }
}