package dropnext.dss.testutil.helper

import java.io.File


/** One Kotlin source file as the architecture tests see it: where it is, what it is called, what it says. */
data class KotlinSourceFile(
  val path: String,
  val name: String,
  val text: String,
) {
  /** [text] with comments blanked out — what a rule that greps for *code* must look at. */
  val code: String by lazy { text.withoutComments() }
}

/**
 * The repository root, found by walking up from the working directory until `settings.gradle.kts`
 * appears. Gradle runs tests with the project directory as the working directory but an IDE run
 * configuration may not, and a rule that silently scans an empty directory passes for the wrong
 * reason.
 */
val projectRoot: File by lazy {
  generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("No settings.gradle.kts above ${System.getProperty("user.dir")}; cannot locate the project root.")
}

/**
 * Every `.kt` file under [relativeDir], read as text. A plain file walk rather than a Konsist scope:
 * the text rules need no parsed syntax tree, and parsing every file just to grep it costs seconds.
 */
fun kotlinSourceFileTexts(relativeDir: String): List<KotlinSourceFile> {
  val root = File(projectRoot, relativeDir)
  require(root.isDirectory) { "No such directory: $root" }
  return root.walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .map { KotlinSourceFile(path = normalizedPath(it.path), name = it.nameWithoutExtension, text = it.readText()) }
    .toList()
}

/** File paths compare with forward slashes on every OS, so an allow-list entry can be written one way. */
fun normalizedPath(path: String): String = path.replace('\\', '/')

fun pathContainsAllowListEntry(path: String, allowList: List<String>): Boolean =
  allowList.any { allowed -> allowed in normalizedPath(path) }

/**
 * Blanks out every comment, keeping the file's length and its line breaks so line numbers still line
 * up. String and character literals are left alone: `"https://x"` is not a comment, and a rule that
 * greps for `Json {` must not fire on a comment *explaining* why a file avoids one.
 */
fun String.withoutComments(): String {
  val out = StringBuilder(length)
  var i = 0
  while (i < length) {
    val char = this[i]
    val next = if (i + 1 < length) this[i + 1] else ' '
    when {
      char == '/' && next == '/' -> {
        while (i < length && this[i] != '\n') {
          out.append(' ')
          i++
        }
      }

      char == '/' && next == '*' -> {
        // Kotlin block comments nest, so count depth rather than stopping at the first close.
        var depth = 0
        while (i < length) {
          if (this[i] == '/' && i + 1 < length && this[i + 1] == '*') {
            depth++
            out.append("  ")
            i += 2
          } else if (this[i] == '*' && i + 1 < length && this[i + 1] == '/') {
            depth--
            out.append("  ")
            i += 2
            if (depth == 0) break
          } else {
            out.append(if (this[i] == '\n') '\n' else ' ')
            i++
          }
        }
      }

      startsWith(TRIPLE_QUOTE, i) -> {
        val end = indexOf(TRIPLE_QUOTE, i + 3)
        val stop = if (end == -1) length else end + 3
        out.append(this, i, stop)
        i = stop
      }

      char == '"' || char == '\'' -> {
        out.append(char)
        i++
        while (i < length && this[i] != char && this[i] != '\n') {
          if (this[i] == '\\' && i + 1 < length) {
            out.append(this[i])
            i++
          }
          out.append(this[i])
          i++
        }
        if (i < length) {
          out.append(this[i])
          i++
        }
      }

      else -> {
        out.append(char)
        i++
      }
    }
  }
  return out.toString()
}

private const val TRIPLE_QUOTE = "\"\"\""
