package dropnext.dss.handler

import dropnext.dss.GraphQLClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.config.DssPaths
import dropnext.dss.config.MonolithPaths
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.legacyIdFromGid
import dropnext.dss.lib.ktor.respondBadGatewayText
import dropnext.dss.lib.ktor.respondBadRequestText
import dropnext.dss.lib.ktor.respondForbiddenText
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.ShopifySignatures
import dropnext.dss.shopify.WebhookSubscriptionStatus
import dropnext.dss.shopify.buildOAuthAuthorizeUrl
import dropnext.dss.shopify.exchangeAuthorizationCode
import dropnext.dss.shopify.isValidSignedOAuthState
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.registerStandardWebhooks
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.dss.shopify.signedOAuthState
import dropnext.graphql.generated.ShopIdentity
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*


private val log = KotlinLogging.logger {}

/** Handlers for the Shopify install / OAuth-callback flow. */
class OAuthHandlers(
  private val dssConfig: DssAppConfig,
  private val httpClient: HttpClient,
  private val gqlClientCache: GraphQLClientCache,
  private val httpMonolithClient: MonolithService?,
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

    val graphQlClient = gqlClientCache.forShop(shop, shopifyConfig.apiVersion)
    val identityResult = graphQlClient.execute(ShopIdentity()) {
      header("X-Shopify-Access-Token", oauthResponse.accessToken)
    }
    val shopNode = identityResult.data?.shop
    val shopId = shopNode?.id?.let { legacyIdFromGid(it) } ?: 0L
    val domain = shopNode?.myshopifyDomain?.let { normalizeShopDomain(it) } ?: shop
    dssConfig.shopAccessTokens[domain] = oauthResponse.accessToken
    log.info { "OAuth token cached in memory for shop=$domain" }

    val monolithPersistHtml =
      persistTokenToMonolithAsHtml(domain, shopId, oauthResponse.accessToken)

    val syncResult =
      graphQlClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = 3))) {
        header("X-Shopify-Access-Token", oauthResponse.accessToken)
      }
    val edgeCount = syncResult.data?.products?.edges?.size ?: 0
    log.info { "SyncProductsPage after OAuth: shop=$shop productEdges=$edgeCount" }

    val callbackUrl = "${shopifyConfig.publicBaseUrl}${DssPaths.WEBHOOKS_SHOPIFY}"
    val webhookReport =
      registerStandardWebhooks(graphQlClient, oauthResponse.accessToken, callbackUrl)

    val html = """
      <html>
        <head><meta name="robots" content="noindex, nofollow"></head>
        <body>
          <h1>App installed</h1>
          <p>Shop: $shop (id $shopId)</p>
          $monolithPersistHtml
          <p>SyncProductsPage (first 3) product edges: $edgeCount</p>
          <p>Webhook callback URL: <code>${htmlEscape(callbackUrl)}</code></p>
          <p>Active <code>products/*</code> and <code>orders/*</code> webhook subscriptions:</p>
          ${renderWebhookList(webhookReport.activeSubscriptions)}
          <p>Webhook subscriptions added in this install:</p>
          ${renderWebhookList(webhookReport.addedSubscriptions)}
          ${renderFailedWebhooks(webhookReport.failedTopics)}
          <p><a href="${DssPaths.DEMO_PRODUCTS}?shop=$shop">${DssPaths.DEMO_PRODUCTS}?shop=$shop</a></p>
          <p><a href="${DssPaths.DEMO_ORDER}?shop=$shop&id=ORDER_GID">${DssPaths.DEMO_ORDER}?shop=$shop&id=...</a></p>
        </body>
      </html>
    """.trimIndent()
    call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
  }

  private suspend fun persistTokenToMonolithAsHtml(
    domain: String,
    shopId: Long,
    accessToken: String
  ): String {
    val monolith = httpMonolithClient ?: return """
        <p style="color:#b45309">
          <strong>Monolith not configured:</strong> <code>MONOLITH_BASE_URL</code>
          is unset, so we did not <code>PUT …${MonolithPaths.STORES_API_KEY}</code>.
          The Shopify Admin token is cached in this server&rsquo;s memory only.
        </p>
        <p>
          Set <code>MONOLITH_BASE_URL</code> (and normally <code>MONOLITH_API_KEY</code>
          for Bearer auth to the monolith) in prod and dev if installs should persist the token DropNext-wide.
        </p>
      """.trimIndent().replace("\n", " ")

    val apiKeyReq = UpdateStoreApiKeyRequest(
      shopifySubdomain = shopifySubdomainShort(domain),
      shopifyShopId = shopId,
      apiKey = accessToken,
    )
    return when (val r = monolith.putStoreApiKey(apiKeyReq)) {
      is StoreApiKeyResult.Ok -> {
        log.info { "Monolith store api-key updated storeId=${r.storeId} shop=$domain" }
        """
          <p style="color:green">
            <strong>Shopify token saved via monolith</strong> (<code>PUT …${MonolithPaths.STORES_API_KEY}</code>, store_id=${r.storeId})
            and cached in memory &mdash; webhooks and routes can use this process immediately.
          </p>
        """
      }

      is StoreApiKeyResult.Error -> {
        logMonolithFailure("putStoreApiKey", r.status, r.parsed, "shop=$domain")
        val detailEsc = htmlEscape((r.parsed?.message ?: "update failed").take(400))
        """
          <p style="color:#b91c1c">
            <strong>Monolith <code>PUT …${MonolithPaths.STORES_API_KEY}</code> failed</strong> (HTTP status ${r.status}).
            Token is cached in this server&rsquo;s memory only. Details: <code>$detailEsc</code>
          </p>
        """.trimIndent().replace("\n", " ")
      }
    }
  }
}

private fun renderWebhookList(subscriptions: List<WebhookSubscriptionStatus>): String =
  if (subscriptions.isEmpty()) {
    "<ul><li>None</li></ul>"
  } else {
    subscriptions.joinToString(prefix = "<ul>", postfix = "</ul>") { webhook ->
      "<li><code>${htmlEscape(webhook.topic.name)}</code> &rarr; <code>${htmlEscape(webhook.uri)}</code> " +
        "(id <code>${htmlEscape(webhook.id)}</code>)</li>"
    }
  }

private fun renderFailedWebhooks(failures: List<Pair<WebhookSubscriptionTopic, String>>): String {
  if (failures.isEmpty()) return ""
  val items = failures.joinToString(prefix = "<ul>", postfix = "</ul>") { (topic, error) ->
    "<li style=\"color:red\"><code>${htmlEscape(topic.name)}</code> &mdash; ${htmlEscape(error)}</li>"
  }
  return "<p style=\"color:red\"><strong>Webhook registrations that failed (check app scopes in Partner Dashboard):</strong></p>$items" +
    "<p>Make sure <code>SHOPIFY_SCOPES</code> includes <code>read_orders,write_fulfillments</code> and " +
    "that your Shopify Partner Dashboard app has <em>Orders</em> API access enabled. " +
    "Reinstall the app after fixing.</p>"
}

private fun htmlEscape(value: String): String =
  value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
