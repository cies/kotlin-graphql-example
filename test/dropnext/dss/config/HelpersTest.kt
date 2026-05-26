package dropnext.dss.config

import kotlin.test.Test

class HelpersTest {

  @Test
  fun `null input returns null`() {
    assert(normalizeQuoted(null) == null)
  }

  @Test
  fun `empty and whitespace inputs return null`() {
    assert(normalizeQuoted("") == null)
    assert(normalizeQuoted("    ") == null)
  }

  @Test
  fun `plain value is trimmed`() {
    assert(normalizeQuoted("  hello  ") == "hello")
  }

  @Test
  fun `strips wrapping double quotes`() {
    assert(normalizeQuoted("\"hello\"") == "hello")
  }

  @Test
  fun `strips wrapping single quotes`() {
    assert(normalizeQuoted("'hello'") == "hello")
  }

  @Test
  fun `quotes containing only whitespace return null`() {
    assert(normalizeQuoted("\"   \"") == null)
    assert(normalizeQuoted("'  '") == null)
  }

  @Test
  fun `strips multiple levels of nested quotes`() {
    assert(normalizeQuoted("\"\"x\"\"") == "x")
    assert(normalizeQuoted("'\"x\"'") == "x")
  }

  @Test
  fun `preserves inner whitespace in unquoted value`() {
    assert(normalizeQuoted("hello world") == "hello world")
  }

  @Test
  fun `preserves inner quotes when only one side is quoted`() {
    assert(normalizeQuoted("\"hello") == "\"hello")
    assert(normalizeQuoted("hello\"") == "hello\"")
  }

  @Test
  fun `parseBool true is case-insensitive`() {
    assert(parseBool("true"))
    assert(parseBool("TRUE"))
    assert(parseBool("True"))
  }

  @Test
  fun `parseBool strips wrapping quotes and whitespace`() {
    assert(parseBool("  true  "))
    assert(parseBool("\"true\""))
    assert(parseBool("' true '"))
  }

  @Test
  fun `parseBool returns false for null empty and non-true values`() {
    assert(!parseBool(null))
    assert(!parseBool(""))
    assert(!parseBool("   "))
    assert(!parseBool("false"))
    assert(!parseBool("1"))
    assert(!parseBool("yes"))
  }
}
