package dropnext.dss

import kotlinx.serialization.json.Json

/** JSON for monolith outbound bodies (generated OpenAPI DTOs). */
val MonolithJson =
  Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
  }
