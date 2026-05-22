package dropnext.dss.lib.monolith

import kotlin.test.Test

class MonolithErrorBodyTest {

  @Test
  fun `parses nested InternalError with trace_id`() {
    val parsed =
      parseMonolithErrorBody(
        """{"error":{"code":"InternalError","message":"Internal server error","trace_id":"93753e87"}}""",
      )
    assert(parsed.monolithTraceId == "93753e87")
    assert(parsed.code == "InternalError")
    assert(parsed.message == "Internal server error")
  }

  @Test
  fun `parses flat string error`() {
    val parsed = parseMonolithErrorBody("""{"error":"Order already exists."}""")
    assert(parsed.message == "Order already exists.")
    assert(parsed.monolithTraceId == null)
  }

  @Test
  fun `formatForLog includes monolith_trace_id`() {
    val parsed = parseMonolithErrorBody("""{"error":{"code":"InternalError","trace_id":"abc"}}""")
    assert(parsed.formatForLog().contains("monolith_trace_id=abc"))
  }

  @Test
  fun `formatForLog reports unknown when all fields are null`() {
    val empty = MonolithErrorBody(message = null, code = null, monolithTraceId = null)
    assert(empty.formatForLog() == "monolith_error=unknown")
  }

  @Test
  fun `formatForLog truncates message at 200 characters`() {
    val longMessage = "x".repeat(500)
    val parsed = MonolithErrorBody(message = longMessage, code = null, monolithTraceId = null)
    val formatted = parsed.formatForLog()
    assert(formatted == "message=${"x".repeat(200)}")
  }

  @Test
  fun `parses empty body to all-null fields`() {
    val parsed = parseMonolithErrorBody("")
    assert(parsed.message == null)
    assert(parsed.code == null)
    assert(parsed.monolithTraceId == null)
  }

  @Test
  fun `parses whitespace-only body to all-null fields`() {
    val parsed = parseMonolithErrorBody("   \n  ")
    assert(parsed.message == null)
  }

  @Test
  fun `parses non-json body as truncated message`() {
    val parsed = parseMonolithErrorBody("plain text oops")
    assert(parsed.message == "plain text oops")
    assert(parsed.code == null)
    assert(parsed.monolithTraceId == null)
  }

  @Test
  fun `parses json array root as truncated message`() {
    val parsed = parseMonolithErrorBody("[1,2,3]")
    val message = parsed.message
    assert(message != null && message.startsWith("[1,2,3"))
  }

  @Test
  fun `reads traceId in camelCase when snake_case is missing`() {
    val parsed = parseMonolithErrorBody("""{"error":{"code":"Boom","traceId":"camel-123"}}""")
    assert(parsed.monolithTraceId == "camel-123")
  }

  @Test
  fun `monolithError falls back to HTTP status when raw body is empty`() {
    val (msg, parsed) = monolithError(500, "")
    assert(msg == "HTTP 500")
    assert(parsed.message == null)
  }

  @Test
  fun `monolithError uses parsed message when available`() {
    val (msg, _) = monolithError(409, """{"error":"Order already exists."}""")
    assert(msg == "Order already exists.")
  }

  @Test
  fun `monolithError uses raw body when no structured message`() {
    val (msg, _) = monolithError(502, "bad gateway raw")
    assert(msg == "bad gateway raw")
  }
}
