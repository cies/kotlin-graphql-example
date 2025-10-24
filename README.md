# Example of using GraphQL from Kotlin

This project is a simple application that uses the
[GraphQL Kotlin Gradle plugin](https://expediagroup.github.io/graphql-kotlin/docs/plugins/gradle-plugin)
to auto-generate GraphQL client data model that's deserialized with `kotlinx.serialization`.
The [Ktor based client](https://github.com/ExpediaGroup/graphql-kotlin/tree/master/clients/graphql-kotlin-ktor-client)
is used to communicate with the open Shopify GraphQL API server (serving a test dataset for development purposes).

It demonstrates:

* Compile-time type checking of GraphQL queries against the schema.
* Proper IDE support for writing queries in IntelliJ IDEA (syntax + error highlighting, and autocomplete).
* Kotlin project (no TypeScript or JavaScript).

It makes use of Shopify's GraphQL API, which has a huge schema (more about the challenges that it posed below).


## The JetBrains [GraphQL IDE plugin](https://plugins.jetbrains.com/plugin/8097-graphql)

With this plugin, you get syntax + error highlighting and autocomplete on GraphQL queries in IntelliJ IDEA.
It also allows you to run queries against an endpoint directly from the IDE.

It seems, though, that the plugin "introspection" (downloading of the schema) does not work well with the Shopify GraphQL API.
Strange enough, it did work for the Pokémon GraphQL API.

To mitigate this, we use the Gradle plugin's introspection feature (the `graphqlIntrospectSchema` task) to fetch the schema.
The default location (in `build/`) was not accessible to the IDE plugin,
so the Gradle GraphQL plugin is configured to put it in `src/main/graphql-schema`.
Using `src/main/graphql.config.yml` we configure the IDE plugin to look for the schema there.


## Building locally

This project uses Gradle and you can build it locally using

```shell script
gradle clean build
```



## TODO

Have a look at this (I dont think it is that useful):

https://github.com/blackmo18/kotlin-shopify
