package dropnext.dss.lib.json

import kotlinx.serialization.json.Json


val AppJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
  explicitNulls = false
  coerceInputValues = true
}
