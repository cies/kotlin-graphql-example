package dropnext.dss.lib.monolith

import dropnext.dss.MonolithJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed monolith error payload. [monolithTraceId] is the backend's `trace_id` (not DSS request trace).
 */
data class MonolithErrorBody(
  val message: String?,
  val code: String?,
  val monolithTraceId: String?,
) {
  fun formatForLog(): String {
    val parts = mutableListOf<String>()
    monolithTraceId?.let { parts += "monolith_trace_id=$it" }
    code?.let { parts += "code=$it" }
    message?.let { parts += "message=${it.take(200)}" }
    return if (parts.isEmpty()) "monolith_error=unknown" else parts.joinToString(" ")
  }
}

/** Message for [MonolithCallError.errorMessage] plus parsed fields for logging. */
fun monolithError(httpStatus: Int, rawBody: String): Pair<String, MonolithErrorBody> {
  val parsed = parseMonolithErrorBody(rawBody)
  val msg = parsed.message ?: rawBody.trim().take(512).ifEmpty { "HTTP $httpStatus" }
  return msg to parsed
}

fun parseMonolithErrorBody(rawBody: String): MonolithErrorBody {
  val trimmed = rawBody.trim()
  if (trimmed.isEmpty()) {
    return MonolithErrorBody(message = null, code = null, monolithTraceId = null)
  }
  return try {
    when (val root = MonolithJson.parseToJsonElement(trimmed)) {
      is JsonObject -> parseErrorField(root["error"])
      else ->
        MonolithErrorBody(
          message = trimmed.take(200),
          code = null,
          monolithTraceId = null,
        )
    }
  } catch (_: Exception) {
    MonolithErrorBody(message = trimmed.take(200), code = null, monolithTraceId = null)
  }
}

private fun parseErrorField(errorElement: kotlinx.serialization.json.JsonElement?): MonolithErrorBody {
  if (errorElement == null) {
    return MonolithErrorBody(message = null, code = null, monolithTraceId = null)
  }
  return when (errorElement) {
    is JsonObject -> {
      val code = errorElement["code"]?.jsonPrimitive?.content
      val message = errorElement["message"]?.jsonPrimitive?.content
      val traceId =
        errorElement["trace_id"]?.jsonPrimitive?.content
          ?: errorElement["traceId"]?.jsonPrimitive?.content
      MonolithErrorBody(message = message, code = code, monolithTraceId = traceId)
    }
    is JsonPrimitive ->
      MonolithErrorBody(message = errorElement.content, code = null, monolithTraceId = null)
    else ->
      MonolithErrorBody(message = errorElement.toString().take(200), code = null, monolithTraceId = null)
  }
}
