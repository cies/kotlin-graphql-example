package dropnext.dss.handler

import dropnext.dss.config.Config
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.lib.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondTextError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.monolith.ShopifyServiceFactory
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import dropnext.dss.lib.shopify.webhook.ShopifySignatures
import dropnext.dss.path.DssPaths
import dropnext.dss.presentation.renderOAuthInstallPage
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.legacyIdFromGid
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText


private val log = KotlinLogging.logger {}

/** Handlers for the Shopify install / OAuth-callback flow. */
class OAuthHandlers(
  private val dssConfig: Config,
  private val oauthClient: ShopifyOAuthService,
  private val shopifyServiceFactory: ShopifyServiceFactory,
  private val monolithService: MonolithService,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val shopifyConfig = dssConfig.shopify

  suspend fun handleInstall(call: ApplicationCall) {
    val rawShop = call.requireParam("shop") ?: return
    val shop = ShopDomain.parse(rawShop) ?: return call.respondTextError(
      DssError.InvalidParameter("shop", "not a valid Shopify domain"),
    )
    val state = oauthClient.signedState(shop)
    call.respondRedirect(oauthClient.authorizeUrl(shop, state))
  }

  suspend fun handleOAuthCallback(call: ApplicationCall) {
    val params = call.request.queryParameters
    val hmac = call.requireParam("hmac") ?: return
    val rawShop = call.requireParam("shop") ?: return
    val shop = ShopDomain.parse(rawShop)
      ?: return call.respondTextError(DssError.InvalidParameter("shop"))
    val state = call.requireParam("state") ?: return
    val code = call.requireParam("code") ?: return

    if (!ShopifySignatures.verifyOAuthCallback(params, shopifyConfig.appClientSecret, hmac)) {
      return call.respondTextError(DssError.InvalidSignature("Invalid HMAC"))
    }
    if (!oauthClient.isSignedStateValid(state, shop)) {
      return call.respondTextError(DssError.InvalidSignature("Invalid or expired state"))
    }

    val oauthResponse = oauthClient.exchangeCode(shop, code)
      .getOrElse { e ->
        log.warn { "OAuth code exchange failed for shop=${shop.host}: ${e.message}" }
        return call.respondTextError(DssError.UpstreamFailure("OAuth failed: could not exchange authorization code"))
      }

    // The just-issued token has not been cached yet, so pass it explicitly to the factory so the
    // subsequent Graphql calls authorise correctly.
    val shopify = shopifyServiceFactory.forShop(shop, explicitToken = oauthResponse.accessToken)
      ?: return call.respondTextError(DssError.UpstreamFailure("could not build ShopifyService for $shop"))

    val identityResult = shopify.shopIdentity()
    val shopNode = identityResult.data?.shop
    val shopId = shopNode?.id?.let { legacyIdFromGid(it) } ?: 0L
    val domain = shopNode?.myshopifyDomain?.let { ShopDomain.parse(it) } ?: shop
    shopTokens[domain] = oauthResponse.accessToken
    log.info { "OAuth token cached in memory for shop=${domain.host}" }

    val monolithPersist = persistTokenToMonolith(domain, shopId, oauthResponse.accessToken)

    val syncResult = shopify.syncProductsPage(first = 3)
    val edgeCount = syncResult.data?.products?.edges?.size ?: 0
    log.info { "SyncProductsPage after OAuth: shop=${shop.host} productEdges=$edgeCount" }

    val callbackUrl = "${shopifyConfig.publicBaseUrl}${DssPaths.WEBHOOKS_SHOPIFY}"
    val webhookReport = shopify.registerStandardWebhooks(callbackUrl)

    val html = renderOAuthInstallPage(
      shop = shop.host,
      shopId = shopId,
      monolithPersist = monolithPersist,
      productEdgeCount = edgeCount,
      webhookCallbackUrl = callbackUrl,
      activeSubscriptions = webhookReport.activeSubscriptions,
      addedSubscriptions = webhookReport.addedSubscriptions,
      failedTopics = webhookReport.failedTopics,
    )
    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
  }

  private suspend fun ApplicationCall.requireParam(name: String): String? =
    request.queryParameters.required(name) ?: run {
      respondTextError(DssError.MissingParameter(name))
      null
    }

  /** Persist the freshly obtained Shopify Admin token to the monolith; return a presentation-layer outcome. */
  private suspend fun persistTokenToMonolith(
    shop: ShopDomain,
    shopId: Long,
    accessToken: String,
  ): MonolithPersistOutcome {
    val apiKeyReq = UpdateStoreApiKeyRequest(
      shopifySubdomain = shop.subdomainShort,
      shopifyShopId = shopId,
      apiKey = accessToken,
    )
    return when (val r = monolithService.putStoreApiKey(apiKeyReq)) {
      is StoreApiKeyResult.Ok -> {
        log.info { "Monolith store api-key updated storeId=${r.storeId} shop=${shop.host}" }
        MonolithPersistOutcome.Persisted(storeId = r.storeId)
      }
      is StoreApiKeyResult.Error -> {
        logMonolithFailure("putStoreApiKey", r.status, r.parsed, "shop=${shop.host}")
        MonolithPersistOutcome.Failed(httpStatus = r.status, detail = r.parsed?.message)
      }
    }
  }
}

private fun Parameters.required(name: String): String? = this[name]
