package com.example.dss

import com.example.dss.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** When [secret] is non-blank, require matching `X-DSS-Internal-Secret` header (constant-time compare). */
suspend fun ApplicationCall.requireDssInternalSecret(secret: String?): Boolean {
  if (secret.isNullOrBlank()) return true
  val provided = request.headers["X-DSS-Internal-Secret"] ?: ""
  if (!constantTimeEquals(secret, provided)) {
    respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
    return false
  }
  return true
}
