package dropnext.dss.lib.ktor

import dropnext.dss.lib.json.AppJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation


/** Installs `ContentNegotiation` with the shared inbound [AppJson] config. */
fun Application.installJsonContentNegotiation() {
  install(ContentNegotiation) {
    json(AppJson)
  }
}
