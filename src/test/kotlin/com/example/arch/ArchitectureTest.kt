package com.example.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class ArchitectureTest {
  @Test
  fun `monolith lib does not depend on Ktor server`() {
    Konsist.scopeFromProject()
      .files
      .withPackage("com.example.lib.monolith")
      .assertFalse { file ->
        file.imports.any { import -> import.name.startsWith("io.ktor.server") }
      }
  }

  @Test
  fun `production code does not reference legacy com example dss package`() {
    Konsist.scopeFromProduction()
      .files
      .assertFalse { file ->
        file.imports.any { import ->
          import.name == "com.example.dss" ||
            import.name.startsWith("com.example.dss.") && !import.name.startsWith("com.example.lib.dss")
        }
      }
  }
}
