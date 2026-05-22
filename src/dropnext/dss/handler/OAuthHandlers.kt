package dropnext.dss.handler

import dropnext.dss.GraphqlClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.legacyIdFromGid
import dropnext.dss.lib.ktor.respondBadGatewayText
import dropnext.dss.lib.ktor.respondBadRequestText
import dropnext.dss.lib.ktor.respondForbiddenText
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.presentation.MonolithPersistOutcome
import dropnext.dss.presentation.OAuthInstallPageModel
import dropnext.dss.presentation.renderOAuthInstallPage
import dropnext.dss.shopify.ShopifySignatures
import dropnext.dss.shopify.buildOAuthAuthorizeUrl
import dropnext.dss.shopify.exchangeAuthorizationCode
import dropnext.dss.shopify.isValidSignedOAuthState
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.registerStandardWebhooks
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.dss.shopify.signedOAuthState
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText


private val log = KotlinLogging.logger {}

/** Handlers for the Shopify install / OAuth-callback flow. */
class OAuthHandlers(
  private val dssConfig: DssAppConfig,
  private val httpClient: HttpClient,
  private val gqlClientCache: GraphqlClientCache,
  private val httpMonolithClient: MonolithService?,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val shopifyConfig = dssConfig.shopify

  suspend fun handleInstall(call: ApplicationCall) {
    val rawShop = call.request.queryParameters["shop"]
      ?: return call.respondBadRequestText("Missing ?shop=your-store.myshopify.com")
    val shop = normalizeShopDomain(rawShop)
      ?: return call.respondBadRequestText("Invalid shop domain")
    val state = signedOAuthState(shop = shop, clientSecret = shopifyConfig.appClientSecret)
    call.respondRedirect(buildOAuthAuthorizeUrl(shop, shopifyConfig, state))
  }

  suspend fun handleOAuthCallback(call: ApplicationCall) {
    val params = call.request.queryParameters
    val hmac = params["hmac"] ?: return call.respondBadRequestText("Missing hmac")
    val rawShop = params["shop"] ?: return call.respondBadRequestText("Missing shop")
    val shop = normalizeShopDomain(rawShop) ?: return call.respondBadRequestText("Invalid shop")
    val state = params["state"] ?: return call.respondBadRequestText("Missing state")
    val code = params["code"] ?: return call.respondBadRequestText("Missing code")

    if (!ShopifySignatures.verifyOAuthCallback(params, shopifyConfig.appClientSecret, hmac)) {
      return call.respondForbiddenText("Invalid HMAC")
    }
    if (!isValidSignedOAuthState(
        state = state,
        expectedShop = shop,
        clientSecret = shopifyConfig.appClientSecret
      )
    ) {
      return call.respondForbiddenText("Invalid or expired state")
    }

    val oauthResponse =
      exchangeAuthorizationCode(httpClient, shop, code, shopifyConfig).getOrElse { e ->
        log.warn { "OAuth code exchange failed for shop=$shop: ${e.message}" }
        return call.respondBadGatewayText("OAuth failed: could not exchange authorization code")
      }

    val gqlClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    val identityResult = gqlClient.execute(ShopIdentity()) {
      header("X-Shopify-Access-Token", oauthResponse.accessToken)
    }
    val shopNode = identityResult.data?.shop
    val shopId = shopNode?.id?.let { legacyIdFromGid(it) } ?: 0L
    val domain = shopNode?.myshopifyDomain?.let { normalizeShopDomain(it) } ?: shop
    shopTokens[domain] = oauthResponse.accessToken
    log.info { "OAuth token cached in memory for shop=$domain" }

    val monolithPersist = persistTokenToMonolith(domain, shopId, oauthResponse.accessToken)

    val syncResult =
      gqlClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = 3))) {
        header("X-Shopify-Access-Token", oauthResponse.accessToken)
      }
    val edgeCount = syncResult.data?.products?.edges?.size ?: 0
    log.info { "SyncProductsPage after OAuth: shop=$shop productEdges=$edgeCount" }

    val callbackUrl = "${shopifyConfig.publicBaseUrl}${DssPaths.WEBHOOKS_SHOPIFY}"
    val webhookReport =
      registerStandardWebhooks(gqlClient, oauthResponse.accessToken, callbackUrl)

    val html = renderOAuthInstallPage(
      OAuthInstallPageModel(
        shop = shop,
        shopId = shopId,
        monolithPersist = monolithPersist,
        productEdgeCount = edgeCount,
        webhookCallbackUrl = callbackUrl,
        activeSubscriptions = webhookReport.activeSubscriptions,
        addedSubscriptions = webhookReport.addedSubscriptions,
        failedTopics = webhookReport.failedTopics,
      ),
    )
    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
  }

  /** Persist the freshly-obtained Shopify Admin token to the monolith; return a presentation-layer outcome. */
  private suspend fun persistTokenToMonolith(
    domain: String,
    shopId: Long,
    accessToken: String,
  ): MonolithPersistOutcome {
    val monolith = httpMonolithClient ?: return MonolithPersistOutcome.MonolithNotConfigured
    val apiKeyReq = UpdateStoreApiKeyRequest(
      shopifySubdomain = shopifySubdomainShort(domain),
      shopifyShopId = shopId,
      apiKey = accessToken,
    )
    return when (val r = monolith.putStoreApiKey(apiKeyReq)) {
      is StoreApiKeyResult.Ok -> {
        log.info { "Monolith store api-key updated storeId=${r.storeId} shop=$domain" }
        MonolithPersistOutcome.Persisted(storeId = r.storeId)
      }
      is StoreApiKeyResult.Error -> {
        logMonolithFailure("putStoreApiKey", r.status, r.parsed, "shop=$domain")
        MonolithPersistOutcome.Failed(httpStatus = r.status, detail = r.parsed?.message)
      }
    }
  }
}
