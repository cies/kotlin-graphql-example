package dropnext.dss.config


/** Reads env values accounting for stray whitespace / wrapped quotes common in Docker UIs. */
object EnvVars {

  /** Returns null when unset or empty after stripping outer ASCII quotes once or more. */
  fun optionalNormalized(name: String): String? = normalizeQuoted(System.getenv(name))

  /** Returns true only when the env var resolves (after quote-stripping) to `true`, case-insensitive. */
  fun optionalBool(name: String): Boolean = parseBool(System.getenv(name))

  /** Pure boolean parsing extracted from [optionalBool] for unit testing. */
  internal fun parseBool(raw: String?): Boolean = normalizeQuoted(raw)?.equals("true", ignoreCase = true) == true

  /** Pure normalization step extracted from [optionalNormalized] for unit testing. */
  internal fun normalizeQuoted(raw: String?): String? {
    if (raw == null) return null
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
