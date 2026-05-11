package dropnext.dss.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import org.junit.jupiter.api.Test

class ArchitectureTest {
  @Test
  fun `monolith lib does not depend on Ktor server`() {
    Konsist.scopeFromProject().files.withPackage("dropnext.dss.lib.monolith").assertFalse { file ->
      file.imports.any { import -> import.name.startsWith("io.ktor.server") }
    }
  }

  @Test
  fun `production code does not import from legacy com example packages`() {
    Konsist.scopeFromProduction().files.assertFalse { file ->
      file.imports.any { import ->
        import.name.startsWith("com.example.") &&
          !import.name.startsWith("com.example.graphql.generated")
      }
    }
  }

  @Test
  fun `tests do not depend on mocking frameworks`() {
    Konsist.scopeFromTest().files.assertFalse { file ->
      file.imports.any { import ->
        import.name.startsWith("io.mockk") || import.name.startsWith("org.mockito")
      }
    }
  }

  @Test
  fun `no star imports anywhere`() {
    Konsist.scopeFromProject().files.assertFalse { file ->
      file.imports.any { it.isWildcard }
    }
  }

  @Test
  fun `production code does not use lateinit`() {
    val offenders = productionKotlinFiles()
      .filter { path -> Files.readString(path).contains("lateinit ") }
    assert(offenders.isEmpty()) {
      "lateinit is forbidden in production: ${offenders.joinToString()}"
    }
  }

  @Test
  fun `production code avoids mutable class properties`() {
    val propertyVarRegex =
      Regex("^\\s*(private|internal|public|protected)?\\s*var\\s+", setOf(RegexOption.MULTILINE))
    val offenders = productionKotlinFiles().filter { path ->
      val content = Files.readString(path)
      propertyVarRegex.containsMatchIn(content)
    }
    assert(offenders.isEmpty()) {
      "mutable var properties are forbidden: ${offenders.joinToString()}"
    }
  }

  @Test
  fun `dss routing stays pure and avoids graphql generated types`() {
    Konsist.scopeFromProduction()
      .files
      .withPackage("dropnext.dss.lib.dss")
      .filter { file -> file.name == "DssRouting.kt" }
      .assertFalse { file ->
        file.imports.any { import -> import.name.startsWith("com.example.graphql.generated") }
      }
  }

  private fun productionKotlinFiles(): List<Path> =
    Files.walk(Path.of("src/main/kotlin"))
      .filter { it.isRegularFile() && it.extension == "kt" && it.name != "ArchitectureTest.kt" }
      .toList()
      .filter { !it.pathString.endsWith("generated") }
}
