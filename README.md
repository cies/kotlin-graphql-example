# Example of using GraphQL from Kotlin

This project is a simple application that use [GraphQL Kotlin Gradle plugin](https://expediagroup.github.io/graphql-kotlin/docs/plugins/gradle-plugin)
to auto-generate GraphQL client data model compatible with `kotlinx.serialization` and then use Ktor based client to communicate
with the target GraphQL server.

It demonstrates:

* Compile-time type checking of GraphQL queries against the schema.
* Proper IDE support for writing queries in IntelliJ IDEA (syntax + error highlighting, and autocomplete).
* Kotlin project (no TypeScript or JavaScript).


## The JetBrains [GraphQL IDE plugin](https://plugins.jetbrains.com/plugin/8097-graphql)

With this plugin, you get syntax + error highlighting and autocomplete on GraphQL queries in IntelliJ IDEA.
It also allows you to run queries against an endpoint directly from the IDE.

It seems though that the plugin "introspection" (downloading of the schema) does not work well with the Shopify GraphQL API.
Strange enough it did work for the Pokémon GraphQL API.

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

* See if we can [**not** use Ktor](https://github.com/ExpediaGroup/graphql-kotlin/issues/2123) (which has a dependency on `kotlin-reflect`)... Either by using http4k, or by using OkHttp directly.
