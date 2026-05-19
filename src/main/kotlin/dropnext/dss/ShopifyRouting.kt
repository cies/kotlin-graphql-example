package dropnext.dss

import dropnext.dss.lib.dss.DssAppConfig
import dropnext.dss.lib.dss.DssHttpHandlers
import dropnext.dss.lib.dss.ShopifyAdminToken
import dropnext.dss.lib.dss.clientErrorMessage
import dropnext.dss.lib.dss.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.dss.dto.DeleteProductVariantsRequest
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.dto.PutShopAccessTokenRequest
import dropnext.dss.lib.dss.dto.PutShopAccessTokenResponse
import dropnext.dss.lib.dss.dto.UpsertProductVariantsRequest
import dropnext.dss.lib.dss.dto.UpdateStoreApiKeyRequest
import dropnext.dss.lib.dss.installDssRoutes
import dropnext.dss.lib.dss.requireDssInternalSecret
import dropnext.dss.lib.dss.legacyIdFromGid
import dropnext.dss.lib.dss.syncShopifyOrderToMonolith
import dropnext.dss.lib.monolith.DeleteVariantsResult
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.StoreApiKeyResult
import dropnext.dss.lib.monolith.UpsertVariantsResult
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.shopify.FulfillmentCreateDemoBody
import dropnext.dss.shopify.FulfillmentTrackingUpdateDemoBody
import dropnext.dss.shopify.ShopifySignatures
import dropnext.dss.shopify.WebhookSubscriptionStatus
import dropnext.dss.shopify.adminGraphqlJsonUrl
import dropnext.dss.shopify.buildOAuthAuthorizeUrl
import dropnext.dss.shopify.exchangeAuthorizationCode
import dropnext.dss.shopify.graphqlResourceIdFromShopifyWebhook
import dropnext.dss.shopify.isValidSignedOAuthState
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.dss.shopify.registerStandardWebhooks
import dropnext.dss.shopify.shopMyshopifyHostFromWebhook
import dropnext.dss.shopify.shopifySubdomainShort
import dropnext.dss.shopify.signedOAuthState
import dropnext.dss.shopify.toProductVariantItems
import dropnext.dss.shopify.variantLegacyIdsFromProductWebhook
import dropnext.dss.config.ShopifyConfig
import com.example.graphql.generated.FulfillmentCreateWithTracking
import com.example.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import com.example.graphql.generated.GetOrderById
import com.example.graphql.generated.GetProductById
import com.example.graphql.generated.ShopIdentity
import com.example.graphql.generated.SyncProductsPage
import com.example.graphql.generated.inputs.FulfillmentTrackingInput
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.configureRouting(
  dssConfig: DssAppConfig,
  httpClient: HttpClient,
  httpMonolithClient: MonolithService?,
  dssHandlers: DssHttpHandlers,
  gqlClientCache: GraphQLClientCache,
) {
  val config: ShopifyConfig = dssConfig.shopify
  val runtimeConfigIssues = runtimeConfigIssues(dssConfig)
  routing {
    get("/") {
      val demoNote = if (dssConfig.enableDemoRoutes) " (enabled)" else " (disabled — set ENABLE_DEMO_ROUTES=true)"
      call.respondText(
        """
        API is running.

        --- Infrastructure / diagnostics ---
          GET  /                        This index
          GET  /health                  Liveness probe — returns "ok"
          GET  /api                     JSON diagnostic info (config, URLs, issues)
          GET  /api/ready               Readiness — 200 if config valid, 400 if not
          GET  /api/check?shop=         Readiness for a specific shop (token + feature flags)
          GET  /api/redirect-url        Full OAuth redirect URL

        --- Shopify OAuth ---
          GET  /install?shop=           Start OAuth — redirects to Shopify authorize URL
          GET  ${config.oauthRedirectPath.padEnd(26)}OAuth callback — code exchange, saves token, registers webhooks

        --- Webhooks ---
          POST /webhooks/shopify        Shopify webhook receiver (products/*, orders/*)

        --- DSS Internal API (X-DSS-Internal-Secret header required if configured) ---
          PUT  /stores/api-key                     Set Shopify Admin token (see repo openapi.json)
          POST /tracking-update                     Monolith webhook: SyncShipmentsWithFulfillmentsRequest → sync Shopify fulfillments
          POST /sync-shipments-with-fulfillments   Monolith webhook: TrackingUpdateRequest → Shopify FulfillmentEvent
          POST /tracking-updates                   Same TrackingUpdate body as .../sync-shipments-with-fulfillments (compat)

        --- Demo routes$demoNote ---
          GET  /demo/products?shop=               List products via Shopify GraphQL
          GET  /demo/order?shop=&id=              Load a single order by GID or numeric ID
          POST /demo/fulfillment/create           Create a fulfillment with tracking
          POST /demo/fulfillment/tracking         Update fulfillment tracking info
        """.trimIndent(),
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }

    get("/api") {
      call.respond(
        ApiStatusResponse(
          status = "ok",
          bind = "0.0.0.0:${config.serverPort}",
          publicBaseUrl = config.publicBaseUrl,
          oauthRedirectPath = config.oauthRedirectPath,
          configIssues = runtimeConfigIssues,
        ),
      )
    }

    get("/api/check") {
      val rawShop = call.request.queryParameters["shop"]
      val normalizedShop = rawShop?.let { normalizeShopDomain(it) }
      val shopParamIssue =
        when {
          rawShop == null -> "Missing query parameter: shop"
          normalizedShop == null -> "Invalid shop domain format"
          else -> null
        }
      val headerToken = call.request.headers["X-Shopify-Access-Token"]
      val hasHeaderToken = !headerToken.isNullOrBlank()
      val hasMappedToken =
        normalizedShop != null &&
          dssConfig.shopAccessTokens[normalizedShop]
            ?.isNotBlank() == true
      val tokenIssue =
        if (hasHeaderToken || hasMappedToken) {
          null
        } else {
          "Missing Admin token. Provide X-Shopify-Access-Token header or configure DSS_SHOP_ACCESS_TOKENS."
        }
      val issues =
        buildList {
          addAll(runtimeConfigIssues)
          if (shopParamIssue != null) add(shopParamIssue)
          if (tokenIssue != null) add(tokenIssue)
        }
      val status = if (issues.isEmpty()) "ready" else "missing_requirements"
      call.respond(
        ApiCheckResponse(
          status = status,
          shop = normalizedShop,
          checks =
            ApiCheckDetails(
              hasHeaderToken = hasHeaderToken,
              hasTokenMappedForShop = hasMappedToken,
              demoRoutesEnabled = dssConfig.enableDemoRoutes,
              testHarnessEnabled = dssConfig.enableTestHarness,
              monolithConfigured = !dssConfig.monolithBaseUrl.isNullOrBlank(),
            ),
          issues = issues,
        ),
      )
    }

    get("/api/ready") {
      if (runtimeConfigIssues.isNotEmpty()) {
        call.respondText(
          "API not ready. Missing/invalid config: ${runtimeConfigIssues.joinToString()}",
          ContentType.Text.Plain,
          HttpStatusCode.BadRequest,
        )
        return@get
      }
      call.respondText("API ready", ContentType.Text.Plain, HttpStatusCode.OK)
    }

    get("/health") { call.respondText("ok") }

    get("/api/redirect-url") {
      call.respondText(config.redirectUrl, ContentType.Text.Plain, HttpStatusCode.OK)
    }

    get("/install") {
      val rawShop =
        call.request.queryParameters["shop"]
          ?: return@get call.respondText(
            "Missing ?shop=your-store.myshopify.com",
            status = HttpStatusCode.BadRequest,
          )
      val shop =
        normalizeShopDomain(rawShop)
          ?: return@get call.respondText("Invalid shop domain", status = HttpStatusCode.BadRequest)
      val state = signedOAuthState(shop = shop, clientSecret = config.apiSecret)
      call.respondRedirect(buildOAuthAuthorizeUrl(shop, config, state))
    }

    get(config.oauthRedirectPath) {
      val params = call.request.queryParameters
      val hmac =
        params["hmac"]
          ?: return@get call.respondText("Missing hmac", status = HttpStatusCode.BadRequest)
      val rawShop =
        params["shop"] ?: return@get call.respondText("Missing shop", status = HttpStatusCode.BadRequest)
      val shop =
        normalizeShopDomain(rawShop)
          ?: return@get call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
      val state =
        params["state"] ?: return@get call.respondText("Missing state", status = HttpStatusCode.BadRequest)
      val code =
        params["code"] ?: return@get call.respondText("Missing code", status = HttpStatusCode.BadRequest)

      if (!ShopifySignatures.verifyOAuthCallback(params, config.apiSecret, hmac)) {
        return@get call.respondText("Invalid HMAC", status = HttpStatusCode.Forbidden)
      }
      if (!isValidSignedOAuthState(state = state, expectedShop = shop, clientSecret = config.apiSecret)) {
        return@get call.respondText("Invalid or expired state", status = HttpStatusCode.Forbidden)
      }

      val oauthResponse =
        exchangeAuthorizationCode(httpClient, shop, code, config).getOrElse { e ->
          log.warn("OAuth code exchange failed for shop=$shop: ${e.message}")
          return@get call.respondText(
            "OAuth failed: could not exchange authorization code",
            status = HttpStatusCode.BadGateway,
          )
        }

      val graphQLClient = gqlClientCache.forShop(shop, config.apiVersion)
      val identityResult =
        graphQLClient.execute(ShopIdentity()) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val shopNode = identityResult.data?.shop
      val shopId =
        shopNode?.id?.let { legacyIdFromGid(it.toString()) } ?: 0L
      val domain =
        shopNode?.myshopifyDomain?.let { normalizeShopDomain(it) } ?: shop
      dssConfig.shopAccessTokens[domain] = oauthResponse.accessToken
      log.info("OAuth token cached in memory for shop=$domain")

      val monolithPersistHtml =
        when (val monolith = httpMonolithClient) {
          null ->
            """<p style="color:#b45309"><strong>Monolith not configured:</strong> <code>MONOLITH_BASE_URL</code>
            is unset, so we did not <code>PUT …/stores/api-key</code>. The Shopify Admin token is cached in this
            server&rsquo;s memory only.</p><p>Set <code>MONOLITH_BASE_URL</code> (and normally <code>MONOLITH_API_KEY</code>
            for Bearer auth to the monolith) in prod and dev if installs should persist the token DropNext-wide.</p>"""
              .trimIndent()
              .replace("\n", " ")
          else -> {
            val apiKeyReq =
              UpdateStoreApiKeyRequest(
                shopifySubdomain = shopifySubdomainShort(domain),
                shopifyShopId = shopId,
                apiKey = oauthResponse.accessToken,
              )
            when (val r = monolith.putStoreApiKey(apiKeyReq)) {
              is StoreApiKeyResult.Ok -> {
                log.info("Monolith store api-key updated storeId=${r.storeId} shop=$domain")
                "<p style=\"color:green\"><strong>Shopify token saved via monolith</strong> (<code>PUT …/stores/api-key</code>, " +
                  "store_id=${r.storeId}) and cached in memory &mdash; webhooks and routes can use this process immediately.</p>"
              }
              is StoreApiKeyResult.Error -> {
                logMonolithFailure(log, "putStoreApiKey", r.status, r.parsed, "shop=$domain")
                val detailEsc = htmlEscape((r.parsed?.message ?: "update failed").take(400))
                """<p style="color:#b91c1c"><strong>Monolith <code>PUT …/stores/api-key</code> failed</strong>
                (HTTP status ${r.status}). Token is cached in this server&rsquo;s memory only. Details:
                <code>$detailEsc</code></p>"""
                  .trimIndent()
                  .replace("\n", " ")
              }
            }
          }
        }

      val syncResult =
        graphQLClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = 3))) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val edgeCount = syncResult.data?.products?.edges?.size ?: 0
      log.info("SyncProductsPage after OAuth: shop=$shop productEdges=$edgeCount")

      val callbackUrl = "${config.publicBaseUrl}/webhooks/shopify"
      val webhookReport =
        registerStandardWebhooks(graphQLClient, oauthResponse.accessToken, callbackUrl, log)

      val html =
        """
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
        <p><a href="/demo/products?shop=$shop">/demo/products?shop=$shop</a></p>
        <p><a href="/demo/order?shop=$shop&id=ORDER_GID">/demo/order?shop=$shop&id=...</a></p>
        </body></html>
        """.trimIndent()
      call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
      call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }

    if (dssConfig.enableDemoRoutes) {
      get("/demo/products") {
        val rawShop =
          call.request.queryParameters["shop"]
            ?: run {
              call.application.log.warn("[demo] GET /demo/products — missing shop query parameter")
              return@get call.respondText(
                "Pass ?shop=your-store.myshopify.com",
                status = HttpStatusCode.BadRequest,
              )
            }

        val shop =
          normalizeShopDomain(rawShop)
            ?: run {
              call.application.log.warn("[demo] GET /demo/products — invalid shop rawShop=$rawShop")
              return@get call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
            }
        val token =
          when (val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, httpMonolithClient)) {
            ShopifyAdminToken.Missing -> {
              call.application.log.warn("[demo] GET /demo/products — no_admin_token shop=$shop")
              call.respondText(
                "No Admin token for this shop. Set DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN (+ SANDBOX_SHOP), or complete OAuth and configure env from the success page.",
                status = HttpStatusCode.Unauthorized,
              )
              return@get
            }
            is ShopifyAdminToken.Resolved -> t.token
          }
        if (dssConfig.sandboxFakeShopify) {
          return@get
            call.respondText(
              "Products (DSS_SANDBOX_FAKE_SHOPIFY — no HTTP to Shopify):\n" +
                "  Demo product [variants: fake-sku@0.00]\n" +
                "hasNextPage=false",
              ContentType.Text.Plain,
              HttpStatusCode.OK,
            )
        }
        val first = call.request.queryParameters["first"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val after = call.request.queryParameters["after"]
        try {
          val graphQLClient = gqlClientCache.forShop(shop, config.apiVersion)
          val result =
            graphQLClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = first, after = after))) {
              header("X-Shopify-Access-Token", token)
            }
          val gqlErrors = result.errors
          if (!gqlErrors.isNullOrEmpty()) {
            call.application.log.warn("[demo] GET /demo/products — graphql_errors shop=$shop errors=$gqlErrors")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = gqlErrors.joinToString { it.message ?: "?" }.take(1_200)))
            return@get
          }
          val conn = result.data?.products
          val lines =
            conn?.edges.orEmpty().map { edge ->
              val v =
                edge.node.variants.edges.joinToString { ve ->
                  val n = ve.node
                  "${n.sku ?: "-"}@${n.price}"
                }
              "${edge.node.title} [variants: $v]"
            }
          val page = conn?.pageInfo
          call.respondText(
            buildString {
              appendLine("Products:")
              lines.forEach { appendLine(it) }
              appendLine("hasNextPage=${page?.hasNextPage} endCursor=${page?.endCursor}")
            },
          )
        } catch (e: Throwable) {
          val msg = clientErrorMessage(e)
          call.application.log.error("[demo] GET /demo/products failed shop=$shop: $msg", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
        }
      }

      get("/demo/order") {
        val rawShop =
          call.request.queryParameters["shop"]
            ?: run {
              call.application.log.warn("[demo] GET /demo/order — missing shop query parameter")
              return@get call.respondText("Pass ?shop=", status = HttpStatusCode.BadRequest)
            }
        val idParam =
          call.request.queryParameters["id"]
            ?: run {
              call.application.log.warn("[demo] GET /demo/order shop=$rawShop — missing id query parameter")
              return@get call.respondText("Pass ?id=gid://shopify/Order/... or numeric id", status = HttpStatusCode.BadRequest)
            }
        val shop =
          normalizeShopDomain(rawShop)
            ?: run {
              call.application.log.warn("[demo] GET /demo/order — invalid_shop rawShop=$rawShop")
              return@get call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
            }
        val token =
          when (val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, httpMonolithClient)) {
            ShopifyAdminToken.Missing -> {
              call.application.log.warn("[demo] GET /demo/order — no_admin_token shop=$shop")
              call.respondText(
                "No Admin token for this shop. Configure DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN.",
                status = HttpStatusCode.Unauthorized,
              )
              return@get
            }
            is ShopifyAdminToken.Resolved -> t.token
          }
        if (dssConfig.sandboxFakeShopify) {
          return@get
            call.respondText(
              "Order (DSS_SANDBOX_FAKE_SHOPIFY — no HTTP to Shopify):\n" +
                "Order #1001 id=$idParam shop=$shop",
              ContentType.Text.Plain,
              HttpStatusCode.OK,
            )
        }
        val orderGid =
          if (idParam.startsWith("gid://")) {
            idParam
          } else {
            val n =
              idParam.toLongOrNull()
                ?: run {
                  call.application.log.warn("[demo] GET /demo/order — invalid_id shop=$shop id=$idParam")
                  return@get call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
                }
            "gid://shopify/Order/$n"
          }
        try {
          val graphQLClient = gqlClientCache.forShop(shop, config.apiVersion)
          val result =
            graphQLClient.execute(GetOrderById(GetOrderById.Variables(orderGid))) {
              header("X-Shopify-Access-Token", token)
            }
          val o = result.data?.order
          if (o == null) {
            call.application.log.warn("[demo] GET /demo/order — order_null shop=$shop orderGid=$orderGid errors=${result.errors}")
            call.respondText("Order not found or error: ${result.errors}", status = HttpStatusCode.NotFound)
            return@get
          }
          val fos =
            o.fulfillmentOrders.edges.joinToString { e ->
              "${e.node.id} status=${e.node.status}"
            }
          call.respondText(
            "Order ${o.name} email=${o.email} financial=${o.displayFinancialStatus} fulfillment=${o.displayFulfillmentStatus}\n" +
              "Fulfillment orders: $fos\n" +
              "Line items: ${o.lineItems.edges.size}",
          )
        } catch (e: Throwable) {
          val msg = clientErrorMessage(e)
          call.application.log.error("[demo] GET /demo/order failed shop=$shop: $msg", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
        }
      }

      post("/demo/fulfillment/create") {
        val body = call.receive<FulfillmentCreateDemoBody>()
        val shop =
          normalizeShopDomain(body.shop)
            ?: run {
              call.application.log.warn("[demo] POST /demo/fulfillment/create — invalid_shop body.shop=${body.shop}")
              return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
            }
        val token =
          when (val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, httpMonolithClient)) {
            ShopifyAdminToken.Missing -> {
              call.application.log.warn("[demo] POST /demo/fulfillment/create — no_admin_token shop=$shop")
              call.respondText(
                "No Admin token for this shop",
                status = HttpStatusCode.Unauthorized,
              )
              return@post
            }
            is ShopifyAdminToken.Resolved -> t.token
          }
        if (dssConfig.sandboxFakeShopify) {
          return@post
            call.respondText(
              "Fulfillment create (DSS_SANDBOX_FAKE_SHOPIFY): ok\ngid://shopify/Fulfillment/9",
              ContentType.Text.Plain,
              HttpStatusCode.OK,
            )
        }
        try {
          val graphQLClient = gqlClientCache.forShop(shop, config.apiVersion)
          val tracking =
            FulfillmentTrackingInput(
              company = body.company,
              number = body.trackingNumber,
              url = body.trackingUrl,
            )
          val r =
            graphQLClient.execute(
              FulfillmentCreateWithTracking(
                FulfillmentCreateWithTracking.Variables(
                  fulfillmentOrderId = body.fulfillmentOrderId,
                  tracking = tracking,
                  notifyCustomer = body.notifyCustomer,
                ),
              ),
            ) {
              header("X-Shopify-Access-Token", token)
            }
          val err =
            r.data?.fulfillmentCreate?.userErrors.orEmpty().joinToString { "${it.field}:${it.message}" }
          if (err.isNotEmpty() || !r.errors.isNullOrEmpty()) {
            call.application.log.warn(
              "[demo] POST /demo/fulfillment/create — user_or_graphql_errors shop=$shop userErrors=$err graphql=${r.errors}",
            )
            call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
          } else {
            call.respondText("Fulfillment created id=${r.data?.fulfillmentCreate?.fulfillment?.id}")
          }
        } catch (e: Throwable) {
          val msg = clientErrorMessage(e)
          call.application.log.error("[demo] POST /demo/fulfillment/create failed shop=$shop: $msg", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
        }
      }

      post("/demo/fulfillment/tracking") {
        val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
        val shop =
          normalizeShopDomain(body.shop)
            ?: run {
              call.application.log.warn("[demo] POST /demo/fulfillment/tracking — invalid_shop body.shop=${body.shop}")
              return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
            }
        val token =
          when (val t = shopifyAdminTokenWithMonolithFallback(shop, dssConfig, httpMonolithClient)) {
            ShopifyAdminToken.Missing -> {
              call.application.log.warn("[demo] POST /demo/fulfillment/tracking — no_admin_token shop=$shop")
              call.respondText("No Admin token for this shop", status = HttpStatusCode.Unauthorized)
              return@post
            }
            is ShopifyAdminToken.Resolved -> t.token
          }
        if (dssConfig.sandboxFakeShopify) {
          return@post
            call.respondText(
              "Tracking update (DSS_SANDBOX_FAKE_SHOPIFY): ok",
              ContentType.Text.Plain,
              HttpStatusCode.OK,
            )
        }
        try {
          val graphQLClient = gqlClientCache.forShop(shop, config.apiVersion)
          val tracking =
            FulfillmentTrackingInput(
              company = body.company,
              number = body.trackingNumber,
              url = body.trackingUrl,
            )
          val r =
            graphQLClient.execute(
              FulfillmentTrackingInfoUpdateMutation(
                FulfillmentTrackingInfoUpdateMutation.Variables(
                  fulfillmentId = body.fulfillmentId,
                  trackingInfoInput = tracking,
                  notifyCustomer = body.notifyCustomer,
                ),
              ),
            ) {
              header("X-Shopify-Access-Token", token)
            }
          val err =
            r.data?.fulfillmentTrackingInfoUpdate?.userErrors.orEmpty().joinToString {
              "${it.field}:${it.message}"
            }
          if (err.isNotEmpty() || !r.errors.isNullOrEmpty()) {
            call.application.log.warn(
              "[demo] POST /demo/fulfillment/tracking — user_or_graphql_errors shop=$shop userErrors=$err graphql=${r.errors}",
            )
            call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
          } else {
            call.respondText("Tracking updated id=${r.data?.fulfillmentTrackingInfoUpdate?.fulfillment?.id}")
          }
        } catch (e: Throwable) {
          val msg = clientErrorMessage(e)
          call.application.log.error("[demo] POST /demo/fulfillment/tracking failed shop=$shop: $msg", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
        }
      }
    }

    put("/stores/api-key") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@put
      val body = call.receive<PutShopAccessTokenRequest>()
      val shop =
        normalizeShopDomain(body.shopifySubdomain)
          ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))

      dssConfig.shopAccessTokens[shop] = body.apiKey
      log.info("PUT /stores/api-key: token cached in memory for shop=$shop")

      httpMonolithClient?.let { monolith ->
        val apiKeyReq =
          UpdateStoreApiKeyRequest(
            shopifySubdomain = shopifySubdomainShort(shop),
            shopifyShopId = body.shopifyShopId ?: 0L,
            apiKey = body.apiKey,
          )
        when (val r = monolith.putStoreApiKey(apiKeyReq)) {
          is StoreApiKeyResult.Ok -> log.info("Monolith store api-key updated storeId=${r.storeId} shop=$shop")
          is StoreApiKeyResult.Error ->
            logMonolithFailure(log, "putStoreApiKey", r.status, r.parsed, "shop=$shop")
        }
      }

      call.respond(PutShopAccessTokenResponse(shop = shop))
    }

    post("/webhooks/shopify") {
      val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
      val topic = call.request.headers["X-Shopify-Topic"] ?: "unknown"
      val shopDomainHeader = call.request.headers["X-Shopify-Shop-Domain"]
      val body = call.receive<ByteArray>()
      if (!ShopifySignatures.verifyWebhook(hmacHeader, config.apiSecret, body)) {
        call.respond(HttpStatusCode.Unauthorized)
        return@post
      }
      val bodyStr = body.decodeToString()
      call.application.log.info(
        "Webhook verified topic=$topic shopDomainHeader=$shopDomainHeader bodyBytes=${body.size}",
      )
      val shopNorm = shopMyshopifyHostFromWebhook(shopDomainHeader)
      val token =
        if (shopNorm != null) {
          when (
            val t =
              shopifyAdminTokenWithMonolithFallback(
                shopNorm,
                dssConfig,
                httpMonolithClient,
                call.application.log,
              )
          ) {
            ShopifyAdminToken.Missing -> null
            is ShopifyAdminToken.Resolved -> t.token
          }
        } else {
          null
        }
      if (shopNorm == null || token == null) {
        call.application.log.warn(
          "Webhook: no Admin token for shopDomainHeader=$shopDomainHeader (configure DSS_SHOP_ACCESS_TOKENS or OAuth env entry)",
        )
        call.respond(HttpStatusCode.OK)
        return@post
      }

      val graphQLClient = gqlClientCache.forShop(shopNorm, config.apiVersion)

      when (topic) {
        "products/create", "products/update" -> {
          val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
          if (id != null) {
            val r =
              graphQLClient.execute(GetProductById(GetProductById.Variables(id))) {
                header("X-Shopify-Access-Token", token)
              }
            val product = r.data?.product
            val title = product?.title
            call.application.log.info("Webhook product synced id=$id title=$title errors=${r.errors}")

            if (product != null && httpMonolithClient != null) {
              val subdomain = shopifySubdomainShort(shopNorm)
              val currencyCode = r.data?.shop?.currencyCode?.name ?: "USD"
              val variantItems = product.toProductVariantItems(currencyCode)
              if (variantItems.isNotEmpty()) {
                val req = UpsertProductVariantsRequest(
                  shopifySubdomain = subdomain,
                  productVariants = variantItems,
                )
                when (val result = httpMonolithClient.upsertProductVariants(req)) {
                  is UpsertVariantsResult.Ok ->
                    call.application.log.info("Monolith upsert variants ok: ${result.upserted} upserted shop=$shopNorm")
                  is UpsertVariantsResult.Error ->
                    call.application.log.warn("Monolith upsert variants failed: status=${result.status} ${result.errorMessage}")
                }
              }
            }
          } else {
            call.application.log.warn("Webhook product: could not parse GraphQL id from body")
          }
        }
        "products/delete" -> {
          val id = graphqlResourceIdFromShopifyWebhook("products/delete", bodyStr)
          call.application.log.info("Webhook product deleted id=$id")

          if (httpMonolithClient != null) {
            val variantIds = variantLegacyIdsFromProductWebhook(bodyStr)
            if (variantIds.isNotEmpty()) {
              val subdomain = shopifySubdomainShort(shopNorm)
              val req = DeleteProductVariantsRequest(
                shopifySubdomain = subdomain,
                productVariantIds = variantIds,
              )
              when (val result = httpMonolithClient.deleteProductVariants(req)) {
                is DeleteVariantsResult.Ok ->
                  call.application.log.info("Monolith delete variants ok: ${result.deleted} deleted shop=$shopNorm")
                is DeleteVariantsResult.Error ->
                  logMonolithFailure(
                    call.application.log,
                    "deleteProductVariants",
                    result.status,
                    result.parsed,
                    "shop=$shopNorm",
                  )
              }
            }
          }
        }
        "orders/create" -> {
          handleOrderWebhook(
            call.application.log,
            dssConfig,
            graphQLClient,
            token,
            shopNorm,
            httpMonolithClient,
            bodyStr,
            topic,
            syncToMonolith = true,
          )
        }
        "orders/updated" -> {
          handleOrderWebhook(
            call.application.log,
            dssConfig,
            graphQLClient,
            token,
            shopNorm,
            httpMonolithClient,
            bodyStr,
            topic,
            syncToMonolith = dssConfig.syncOrderOnUpdated,
          )
        }
        else -> call.application.log.info("Webhook topic not handled: $topic")
      }
      call.respond(HttpStatusCode.OK)
    }
  }
  installDssRoutes(dssHandlers)
}

private suspend fun handleOrderWebhook(
  log: org.slf4j.Logger,
  dssConfig: DssAppConfig,
  graphQLClient: GraphQLKtorClient,
  token: String,
  shopNorm: String,
  httpMonolithClient: MonolithService?,
  bodyStr: String,
  topic: String,
  syncToMonolith: Boolean,
) {
  val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
  if (id == null) {
    log.warn("Webhook order: could not parse GraphQL id from body")
    return
  }
  if (syncToMonolith && httpMonolithClient != null) {
    syncShopifyOrderToMonolith(log, graphQLClient, token, shopNorm, httpMonolithClient, id, topic)
    return
  }
  if (!syncToMonolith && httpMonolithClient != null) {
    log.info("Webhook $topic: monolith order sync skipped (set DSS_SYNC_ORDER_ON_UPDATED=true to enable)")
  }
  val r =
    graphQLClient.execute(GetOrderById(GetOrderById.Variables(id))) {
      header("X-Shopify-Access-Token", token)
    }
  val name = r.data?.order?.name
  log.info("Webhook order loaded id=$id name=$name errors=${r.errors}")
}

private fun runtimeConfigIssues(dssConfig: DssAppConfig): List<String> {
  val config = dssConfig.shopify
  val issues = mutableListOf<String>()

  if (isPlaceholder(config.apiKey)) {
    issues += "SHOPIFY_APP_CLIENT_ID (or SHOPIFY_API_KEY) is placeholder"
  }
  if (isPlaceholder(config.apiSecret)) {
    issues += "SHOPIFY_APP_CLIENT_SECRET (or SHOPIFY_API_SECRET) is placeholder"
  }
  if (isPlaceholder(config.publicBaseUrl) || config.publicBaseUrl.contains("example.com", ignoreCase = true)) {
    issues += "PUBLIC_BASE_URL is placeholder"
  }
  if (!config.publicBaseUrl.startsWith("https://", ignoreCase = true)) {
    issues += "PUBLIC_BASE_URL should use https://"
  }
  if (dssConfig.dssInternalSecret?.contains("change_this") == true) {
    issues += "DSS_INTERNAL_SECRET is still the placeholder value — set a real secret"
  }
  if (dssConfig.dssInternalSecret != null && dssConfig.dssInternalSecret.length < 32) {
    issues += "DSS_INTERNAL_SECRET should be at least 32 characters"
  }

  return issues
}

private fun isPlaceholder(value: String): Boolean =
  value.contains("your_", ignoreCase = true) || value.contains("change_me", ignoreCase = true)

private fun renderWebhookList(subscriptions: List<WebhookSubscriptionStatus>): String =
  if (subscriptions.isEmpty()) {
    "<ul><li>None</li></ul>"
  } else {
    subscriptions.joinToString(prefix = "<ul>", postfix = "</ul>") { webhook ->
      "<li><code>${htmlEscape(webhook.topic.name)}</code> &rarr; <code>${htmlEscape(webhook.uri)}</code> " +
        "(id <code>${htmlEscape(webhook.id)}</code>)</li>"
    }
  }

private fun renderFailedWebhooks(failures: List<Pair<com.example.graphql.generated.enums.WebhookSubscriptionTopic, String>>): String {
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

@Serializable
private data class ApiStatusResponse(
  val status: String,
  val bind: String,
  val publicBaseUrl: String,
  val oauthRedirectPath: String,
  val configIssues: List<String>,
)

@Serializable
private data class ApiCheckResponse(
  val status: String,
  val shop: String?,
  val checks: ApiCheckDetails,
  val issues: List<String>,
)

@Serializable
private data class ApiCheckDetails(
  val hasHeaderToken: Boolean,
  val hasTokenMappedForShop: Boolean,
  val demoRoutesEnabled: Boolean,
  val testHarnessEnabled: Boolean,
  val monolithConfigured: Boolean,
)
