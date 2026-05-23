package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages


private val log = KotlinLogging.logger {}

/** Catches anything that escapes a handler — returns a generic 500 so secrets never leak into the body. */
fun Application.installDssStatusPages() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      log.error(cause) { "Unhandled error on ${call.request.local.method.value} ${call.request.local.uri}" }
      call.respondErrorText("internal error")
    }
  }
}

/** Installs `ContentNegotiation` with the shared inbound [AppJson] config. */
fun Application.installJsonContentNegotiation() {
  install(ContentNegotiation) {
    json(AppJson)
  }
}
