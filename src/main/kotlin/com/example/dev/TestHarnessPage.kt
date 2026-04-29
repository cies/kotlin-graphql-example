package com.example.dev

/** HTML for <code>GET /dev/test-harness</code> (classpath: <code>/dev/test-harness.html</code>). */
internal object TestHarnessPage {
  fun html(): String =
    TestHarnessPage::class.java.getResourceAsStream("/dev/test-harness.html")?.use {
      it.readBytes().decodeToString()
    }
      ?: error("Missing classpath resource /dev/test-harness.html")
}
