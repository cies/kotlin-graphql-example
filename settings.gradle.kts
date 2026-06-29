// Without this, the resulting binary gets the name of the parent directory
// (breaks Docker image / Gradle distribution naming).
rootProject.name = "dropnext-shopify-service"

// Auto-downloads JDK toolchains (e.g. Java 25 required by build.gradle.kts) when not installed locally.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
