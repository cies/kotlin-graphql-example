package dropnext.dss.handler

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.ktor.respondTextError
import io.ktor.server.application.ApplicationCall


/** Parses [raw] to a [ShopDomain], or answers a JSON `400` naming [parameterName] and returns `null`. */
suspend fun ApplicationCall.shopDomainOrRespond(raw: String, parameterName: String): ShopDomain? =
  ShopDomain.parse(raw) ?: run {
    respondError(DssError.InvalidParameter(parameterName, "not a valid Shopify domain"))
    null
  }

/** The plain-text twin of [shopDomainOrRespond] for the OAuth routes. */
suspend fun ApplicationCall.shopDomainOrRespondText(raw: String, parameterName: String): ShopDomain? =
  ShopDomain.parse(raw) ?: run {
    respondTextError(DssError.InvalidParameter(parameterName, "not a valid Shopify domain"))
    null
  }
