package com.example

import com.example.dev.TestHarnessPage
import com.example.lib.dss.DssAppConfig
import com.example.lib.dss.DssHttpHandlers
import com.example.lib.dss.ShopifyAdminToken
import com.example.lib.dss.clientErrorMessage
import com.example.lib.dss.dto.ErrorResponse
import com.example.lib.dss.installDssRoutes
import com.example.lib.dss.legacyIdFromGid
import com.example.lib.dss.orderToCreateShopifyOrderRequest
import com.example.lib.dss.shopifyAdminTokenForNormalizedShop
import com.example.config.ShopifyConfig
import com.example.graphql.generated.FulfillmentCreateWithTracking
import com.example.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import com.example.graphql.generated.GetOrderById
import com.example.graphql.generated.GetOrderForDss
import com.example.graphql.generated.GetProductById
import com.example.graphql.generated.ShopIdentity
import com.example.graphql.generated.SyncProductsPage
import com.example.graphql.generated.inputs.FulfillmentTrackingInput
import com.example.lib.monolith.MonolithCreateOrderPort
import com.example.shopify.FulfillmentCreateDemoBody
import com.example.shopify.FulfillmentTrackingUpdateDemoBody
import com.example.shopify.OAuthStateStore
import com.example.shopify.ShopifySignatures
import com.example.shopify.adminGraphqlJsonUrl
import com.example.shopify.buildOAuthAuthorizeUrl
import com.example.shopify.exchangeAuthorizationCode
import com.example.shopify.graphqlResourceIdFromShopifyWebhook
import com.example.shopify.normalizeShopDomain
import com.example.shopify.randomOAuthState
import com.example.shopify.registerStandardWebhooks
import com.example.shopify.shopifySubdomainShort
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.URI

fun Application.configureRouting(
  dssConfig: DssAppConfig,
  stateStore: OAuthStateStore,
  httpClient: HttpClient,
  httpMonolithClient: MonolithCreateOrderPort?,
  dssHandlers: DssHttpHandlers,
) {
  val config: ShopifyConfig = dssConfig.shopify
  routing {
    get("/health") { call.respondText("ok") }

    get("/dev/test-harness") {
      if (dssConfig.enableTestHarness) {
        call.respondText(TestHarnessPage.html(), ContentType.Text.Html, HttpStatusCode.OK)
      } else {
        val msg =
          """
          <!DOCTYPE html>
          <html><head><meta charset="utf-8"/><title>Test harness disabled</title></head>
          <body style="font-family:system-ui;max-width:40rem;margin:2rem">
          <h1>Test harness is off</h1>
          <p>The API tester at this URL is disabled by default.</p>
          <p><strong>PowerShell (same window as <code>gradlew run</code>):</strong></p>
          <pre style="background:#f4f4f5;padding:1rem">${'$'}env:ENABLE_TEST_HARNESS = "true"; .\gradlew.bat run</pre>
          <p>Or in IntelliJ / VS Code, add environment variable <code>ENABLE_TEST_HARNESS=true</code> to your run configuration, then restart the server and reload this page.</p>
          <p><a href="/health">GET /health</a> to confirm the app is up.</p>
          </body></html>
          """.trimIndent()
        call.respondText(msg, ContentType.Text.Html, HttpStatusCode.OK)
      }
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
      val state = randomOAuthState()
      stateStore.put(state, shop)
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
      val expectedShop = stateStore.remove(state)
      if (expectedShop == null || expectedShop != shop) {
        return@get call.respondText("Invalid or expired state", status = HttpStatusCode.Forbidden)
      }

      val oauthResponse = exchangeAuthorizationCode(httpClient, shop, code, config)
      val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
      val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
      val identityResult =
        graphQLClient.execute(ShopIdentity()) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val shopNode = identityResult.data?.shop
      val shopId =
        shopNode?.id?.let { legacyIdFromGid(it.toString()) } ?: 0L
      val domain =
        shopNode?.myshopifyDomain?.let { normalizeShopDomain(it) } ?: shop
      val tokenForEnv = "$domain|${oauthResponse.accessToken}"

      val syncResult =
        graphQLClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = 3))) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val edgeCount = syncResult.data?.products?.edges?.size ?: 0
      log.info("SyncProductsPage after OAuth: shop=$shop productEdges=$edgeCount")

      val callbackUrl = "${config.publicBaseUrl}/webhooks/shopify"
      registerStandardWebhooks(graphQLClient, oauthResponse.accessToken, callbackUrl, log)

      val html =
        """
        <html><body>
        <h1>App installed</h1>
        <p>Shop: $shop (id $shopId)</p>
        <p><strong>Stateless mode:</strong> this server does not persist tokens. Add the pair below to
        <code>DSS_SHOP_ACCESS_TOKENS</code> (comma-separated <code>shop.myshopify.com|shpat_…</code>) or pass
        <code>X-Shopify-Access-Token</code> on each DSS request.</p>
        <p>Example entry:</p>
        <pre style="background:#f4f4f5;padding:0.75rem;overflow:auto">$tokenForEnv</pre>
        <p>SyncProductsPage (first 3) product edges: $edgeCount</p>
        <p>Webhooks: PRODUCTS_* and ORDERS_* registered (see server logs for per-topic status).</p>
        <p><a href="/demo/products?shop=$shop">/demo/products?shop=$shop</a> (requires token in env map)</p>
        <p><a href="/demo/order?shop=$shop&id=ORDER_GID">/demo/order?shop=$shop&id=...</a></p>
        </body></html>
        """.trimIndent()
      call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }

    if (dssConfig.enableDemoRoutes) {
      get("/demo/products") {
        val rawShop =
          call.request.queryParameters["shop"]
            ?: return@get
              call.respondText(
                "Pass ?shop=your-store.myshopify.com",
                status = HttpStatusCode.BadRequest,
              )

        val shop =
          normalizeShopDomain(rawShop)
            ?: return@get call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
        val token =
          when (val t = shopifyAdminTokenForNormalizedShop(shop, dssConfig)) {
            ShopifyAdminToken.Missing -> {
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
          val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
          val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
          val result =
            graphQLClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = first, after = after))) {
              header("X-Shopify-Access-Token", token)
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
          call.application.log.warn("GET /demo/products failed", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
        }
      }

      get("/demo/order") {
        val rawShop =
          call.request.queryParameters["shop"]
            ?: return@get call.respondText("Pass ?shop=", status = HttpStatusCode.BadRequest)
        val idParam =
          call.request.queryParameters["id"]
            ?: return@get call.respondText("Pass ?id=gid://shopify/Order/... or numeric id", status = HttpStatusCode.BadRequest)
        val shop =
          normalizeShopDomain(rawShop)
            ?: return@get call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
        val token =
          when (val t = shopifyAdminTokenForNormalizedShop(shop, dssConfig)) {
            ShopifyAdminToken.Missing -> {
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
            val n = idParam.toLongOrNull() ?: return@get call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
            "gid://shopify/Order/$n"
          }
        try {
          val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
          val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
          val result =
            graphQLClient.execute(GetOrderById(GetOrderById.Variables(orderGid))) {
              header("X-Shopify-Access-Token", token)
            }
          val o = result.data?.order
          if (o == null) {
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
          call.application.log.warn("GET /demo/order failed", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
        }
      }

      post("/demo/fulfillment/create") {
        val body = call.receive<FulfillmentCreateDemoBody>()
        val shop =
          normalizeShopDomain(body.shop)
            ?: return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
        val token =
          when (val t = shopifyAdminTokenForNormalizedShop(shop, dssConfig)) {
            ShopifyAdminToken.Missing -> {
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
          val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
          val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
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
            call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
          } else {
            call.respondText("Fulfillment created id=${r.data?.fulfillmentCreate?.fulfillment?.id}")
          }
        } catch (e: Throwable) {
          call.application.log.warn("POST /demo/fulfillment/create failed", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
        }
      }

      post("/demo/fulfillment/tracking") {
        val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
        val shop =
          normalizeShopDomain(body.shop)
            ?: return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
        val token =
          when (val t = shopifyAdminTokenForNormalizedShop(shop, dssConfig)) {
            ShopifyAdminToken.Missing -> {
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
          val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
          val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
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
            call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
          } else {
            call.respondText("Tracking updated id=${r.data?.fulfillmentTrackingInfoUpdate?.fulfillment?.id}")
          }
        } catch (e: Throwable) {
          call.application.log.warn("POST /demo/fulfillment/tracking failed", e)
          call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
        }
      }
    }

    post("/webhooks/shopify") {
      val hmacHeader = call.request.headers["X-Shopify-Hmac-Sha256"]
      val topic = call.request.headers["X-Shopify-Topic"] ?: "unknown"
      val shopDomain = call.request.headers["X-Shopify-Shop-Domain"] ?: "unknown"
      val body = call.receive<ByteArray>()
      if (!ShopifySignatures.verifyWebhook(hmacHeader, config.apiSecret, body)) {
        call.respond(HttpStatusCode.Unauthorized)
        return@post
      }
      val bodyStr = body.decodeToString()
      call.application.log.info(
        "Webhook verified topic=$topic shopDomain=$shopDomain bodyBytes=${body.size}",
      )

      val shopNorm = normalizeShopDomain(shopDomain)
      val token =
        if (shopNorm != null) {
          when (val t = shopifyAdminTokenForNormalizedShop(shopNorm, dssConfig)) {
            ShopifyAdminToken.Missing -> null
            is ShopifyAdminToken.Resolved -> t.token
          }
        } else {
          null
        }
      if (shopNorm == null || token == null) {
        call.application.log.warn(
          "Webhook: no Admin token for shopDomain=$shopDomain (configure DSS_SHOP_ACCESS_TOKENS or OAuth env entry)",
        )
        call.respond(HttpStatusCode.OK)
        return@post
      }

      val gqlUrl = URI(adminGraphqlJsonUrl(shopNorm, config.apiVersion)).toURL()
      val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)

      when (topic) {
        "products/create", "products/update" -> {
          val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
          if (id != null) {
            val r =
              graphQLClient.execute(GetProductById(GetProductById.Variables(id))) {
                header("X-Shopify-Access-Token", token)
              }
            val title = r.data?.product?.title
            call.application.log.info("Webhook product synced id=$id title=$title errors=${r.errors}")
          } else {
            call.application.log.warn("Webhook product: could not parse GraphQL id from body")
          }
        }
        "products/delete" -> {
          val id = graphqlResourceIdFromShopifyWebhook("products/delete", bodyStr)
          call.application.log.info("Webhook product deleted id=$id")
        }
        "orders/create" -> {
          val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
          if (id != null && httpMonolithClient != null) {
            val r =
              graphQLClient.execute(GetOrderForDss(GetOrderForDss.Variables(id))) {
                header("X-Shopify-Access-Token", token)
              }
            val order = r.data?.order
            if (order != null) {
              val req =
                orderToCreateShopifyOrderRequest(shopifySubdomainShort(shopNorm), order)
              httpMonolithClient.postCreateOrder(req).fold(
                onSuccess = {
                  call.application.log.info("Monolith create order ok: httpStatus=${it.status}")
                },
                onFailure = { e -> call.application.log.warn("Monolith create order failed", e) },
              )
            } else {
              call.application.log.warn("Webhook orders/create: order null errors=${r.errors}")
            }
          } else if (id != null) {
            val r =
              graphQLClient.execute(GetOrderById(GetOrderById.Variables(id))) {
                header("X-Shopify-Access-Token", token)
              }
            val name = r.data?.order?.name
            call.application.log.info("Webhook order loaded id=$id name=$name errors=${r.errors}")
          } else {
            call.application.log.warn("Webhook order: could not parse GraphQL id from body")
          }
        }
        "orders/updated" -> {
          val id = graphqlResourceIdFromShopifyWebhook(topic, bodyStr)
          if (id != null) {
            val r =
              graphQLClient.execute(GetOrderById(GetOrderById.Variables(id))) {
                header("X-Shopify-Access-Token", token)
              }
            val name = r.data?.order?.name
            call.application.log.info("Webhook order loaded id=$id name=$name errors=${r.errors}")
          } else {
            call.application.log.warn("Webhook order: could not parse GraphQL id from body")
          }
        }
        else -> call.application.log.info("Webhook topic not handled: $topic")
      }
      call.respond(HttpStatusCode.OK)
    }
  }
  installDssRoutes(dssHandlers)
}
