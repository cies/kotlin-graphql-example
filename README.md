# Example of using GraphQL from Kotlin

* Compile-time type checking of GraphQL queries against the schema.
* Proper IDE support for writing queries in IntelliJ IDEA.
* Kotlin project.

This project is a simple application that use [GraphQL Kotlin Gradle plugin](https://expediagroup.github.io/graphql-kotlin/docs/plugins/gradle-plugin)
to auto-generate GraphQL client data model compatible with `kotlinx.serialization` and then use Ktor based client to communicate
with the target GraphQL server.

## Building locally

This project uses Gradle and you can build it locally using

```shell script
gradle clean build
```

## TODO

* See if we can **not** use Ktor (which has a dependency on `kotlin-reflect`)... Either by using http4k, or by using OkHttp directly.
* Get it to work with Shopify.