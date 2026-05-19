package dropnext.dss

import java.io.File
import kotlin.test.Test

/**
 * Lightweight style guards without Konsist (avoids pulling kotlin-compiler-embeddable on JDK 24+).
 */
class CodeStyleKonsistTest {

  private val mainKotlinRoot = File("src/main/kotlin")

  @Test
  fun `main sources do not use wildcard imports`() {
    val violations = mutableListOf<String>()
    mainKotlinRoot
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .forEach { file ->
        file.readLines().forEachIndexed { index, line ->
          val trimmed = line.trim()
          if (trimmed.startsWith("import ") && trimmed.endsWith(".*")) {
            violations += "${file.path}:${index + 1}: $trimmed"
          }
        }
      }
    assert(violations.isEmpty()) { "Wildcard imports found:\n${violations.joinToString("\n")}" }
  }

  @Test
  fun `no hand-written Kotlin under lib dss dto in main`() {
    val dtoDir = File(mainKotlinRoot, "dropnext/dss/lib/dss/dto")
    val handWritten =
      if (dtoDir.isDirectory) {
        dtoDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
      } else {
        emptyList()
      }
    assert(handWritten.isEmpty()) {
      "Hand-written DTOs belong in openapi.json codegen only: ${handWritten.map { it.path }}"
    }
  }
}
