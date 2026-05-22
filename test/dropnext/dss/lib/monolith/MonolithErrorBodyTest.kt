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
}
