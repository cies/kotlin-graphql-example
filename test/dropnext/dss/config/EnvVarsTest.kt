package dropnext.dss.config

import kotlin.test.Test

class EnvVarsTest {

  @Test
  fun `null input returns null`() {
    assert(EnvVars.normalizeQuoted(null) == null)
  }

  @Test
  fun `empty and whitespace inputs return null`() {
    assert(EnvVars.normalizeQuoted("") == null)
    assert(EnvVars.normalizeQuoted("    ") == null)
  }

  @Test
  fun `plain value is trimmed`() {
    assert(EnvVars.normalizeQuoted("  hello  ") == "hello")
  }

  @Test
  fun `strips wrapping double quotes`() {
    assert(EnvVars.normalizeQuoted("\"hello\"") == "hello")
  }

  @Test
  fun `strips wrapping single quotes`() {
    assert(EnvVars.normalizeQuoted("'hello'") == "hello")
  }

  @Test
  fun `quotes containing only whitespace return null`() {
    assert(EnvVars.normalizeQuoted("\"   \"") == null)
    assert(EnvVars.normalizeQuoted("'  '") == null)
  }

  @Test
  fun `strips multiple levels of nested quotes`() {
    assert(EnvVars.normalizeQuoted("\"\"x\"\"") == "x")
    assert(EnvVars.normalizeQuoted("'\"x\"'") == "x")
  }

  @Test
  fun `preserves inner whitespace in unquoted value`() {
    assert(EnvVars.normalizeQuoted("hello world") == "hello world")
  }

  @Test
  fun `preserves inner quotes when only one side is quoted`() {
    assert(EnvVars.normalizeQuoted("\"hello") == "\"hello")
    assert(EnvVars.normalizeQuoted("hello\"") == "hello\"")
  }

  @Test
  fun `parseBool true is case-insensitive`() {
    assert(EnvVars.parseBool("true"))
    assert(EnvVars.parseBool("TRUE"))
    assert(EnvVars.parseBool("True"))
  }

  @Test
  fun `parseBool strips wrapping quotes and whitespace`() {
    assert(EnvVars.parseBool("  true  "))
    assert(EnvVars.parseBool("\"true\""))
    assert(EnvVars.parseBool("' true '"))
  }

  @Test
  fun `parseBool returns false for null empty and non-true values`() {
    assert(!EnvVars.parseBool(null))
    assert(!EnvVars.parseBool(""))
    assert(!EnvVars.parseBool("   "))
    assert(!EnvVars.parseBool("false"))
    assert(!EnvVars.parseBool("1"))
    assert(!EnvVars.parseBool("yes"))
  }
}
