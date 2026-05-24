package dropnext.dss.lib.ktor

import dropnext.dss.testing.fake.withEmbeddedServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.Test

class TracingTest {

  @Test
  fun `echoes incoming X-Trace-Id back on the response`() {
    withEmbeddedServer({
      installTraceId()
      routing { get("/echo") { call.respondText(call.dssTraceId()) } }
    }) { baseUrl, client ->
      val response = client.get("$baseUrl/echo") { header("X-Trace-Id", "trace-abc-123") }
      assert(response.headers["X-Trace-Id"] == "trace-abc-123")
      assert(response.bodyAsText() == "trace-abc-123")
    }
  }

  @Test
  fun `X-Request-Id takes precedence over X-Trace-Id`() {
    withEmbeddedServer({
      installTraceId()
      routing { get("/echo") { call.respondText(call.dssTraceId()) } }
    }) { baseUrl, client ->
      val response =
        client.get("$baseUrl/echo") {
          header("X-Request-Id", "req-id-wins")
          header("X-Trace-Id", "trace-loses")
        }
      assert(response.bodyAsText() == "req-id-wins")
      assert(response.headers["X-Trace-Id"] == "req-id-wins")
    }
  }

  @Test
  fun `generates a 16-char hex id when no header is provided`() {
    withEmbeddedServer({
      installTraceId()
      routing { get("/echo") { call.respondText(call.dssTraceId()) } }
    }) { baseUrl, client ->
      val response = client.get("$baseUrl/echo")
      val id = response.bodyAsText()
      assert(id.length == 16)
      assert(id.all { it in '0'..'9' || it in 'a'..'f' })
      assert(response.headers["X-Trace-Id"] == id)
    }
  }

  @Test
  fun `blank trace header is treated as missing`() {
    withEmbeddedServer({
      installTraceId()
      routing { get("/echo") { call.respondText(call.dssTraceId()) } }
    }) { baseUrl, client ->
      val response = client.get("$baseUrl/echo") { header("X-Trace-Id", "   ") }
      val id = response.bodyAsText()
      assert(id.length == 16)
      assert(id != "   ")
    }
  }
}
