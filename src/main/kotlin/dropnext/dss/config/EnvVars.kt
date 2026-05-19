package dropnext.dss.config

/**
 * Reads env values accounting for stray whitespace / wrapped quotes common in Docker UIs.
 */
object EnvVars {
  /** Returns null when unset or empty after stripping outer ASCII quotes once or more. */
  fun optionalNormalized(name: String): String? {
    val raw = System.getenv(name) ?: return null
    var s = raw.trim()
    while (s.length >= 2) {
      val inner =
        when {
          s.startsWith('"') && s.endsWith('"') -> s.substring(1, s.length - 1).trim()
          s.startsWith('\'') && s.endsWith('\'') -> s.substring(1, s.length - 1).trim()
          else -> break
        }
      if (inner.isEmpty()) return null
      s = inner
    }
    return s.takeUnless { it.isEmpty() }
  }
}
