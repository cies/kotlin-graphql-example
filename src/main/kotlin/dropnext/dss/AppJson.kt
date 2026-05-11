package dropnext.dss

import kotlinx.serialization.json.Json

val AppJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}
