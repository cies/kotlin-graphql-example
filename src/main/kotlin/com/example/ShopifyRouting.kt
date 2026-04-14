package com.example

import com.example.config.ShopifyConfig
import com.example.graphql.generated.FulfillmentCreateWithTracking
import com.example.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import com.example.graphql.generated.GetOrderById
import com.example.graphql.generated.GetProductById
import com.example.graphql.generated.SyncProductsPage
import com.example.graphql.generated.inputs.FulfillmentTrackingInput
import com.example.shopify.AccessTokenStore
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
import kotlin.math.min

fun Application.configureRouting(
  config: ShopifyConfig,
  stateStore: OAuthStateStore,
  tokenStore: AccessTokenStore,
  httpClient: HttpClient,
) {
  routing {
    get("/health") { call.respondText("ok") }

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
      tokenStore.put(shop, oauthResponse.accessToken)

      val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
      val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)

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
        <p>Shop: $shop</p>
        <p>SyncProductsPage (first 3) product edges: $edgeCount</p>
        <p>Webhooks: PRODUCTS_* and ORDERS_* registered (see server logs for per-topic status).</p>
        <p><a href="/demo/products?shop=$shop">/demo/products?shop=$shop</a></p>
        <p><a href="/demo/order?shop=$shop&id=ORDER_GID">/demo/order?shop=$shop&id=...</a></p>
        </body></html>
        """.trimIndent()
      call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }

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
        tokenStore[shop]
          ?: return@get
            call.respondText(
              "Shop not installed. Open /install?shop=$shop first.",
              status = HttpStatusCode.NotFound,
            )
      val first = call.request.queryParameters["first"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
      val after = call.request.queryParameters["after"]
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
        tokenStore[shop]
          ?: return@get
            call.respondText(
              "Shop not installed. Open /install?shop=$shop first.",
              status = HttpStatusCode.NotFound,
            )
      val orderGid =
        if (idParam.startsWith("gid://")) {
          idParam
        } else {
          val n = idParam.toLongOrNull() ?: return@get call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
          "gid://shopify/Order/$n"
        }
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
    }

    post("/demo/fulfillment/create") {
      val body = call.receive<FulfillmentCreateDemoBody>()
      val shop =
        normalizeShopDomain(body.shop)
          ?: return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
      val token =
        tokenStore[shop]
          ?: return@post
            call.respondText(
              "Shop not installed",
              status = HttpStatusCode.NotFound,
            )
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
    }

    post("/demo/fulfillment/tracking") {
      val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
      val shop =
        normalizeShopDomain(body.shop)
          ?: return@post call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
      val token =
        tokenStore[shop]
          ?: return@post call.respondText("Shop not installed", status = HttpStatusCode.NotFound)
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
      val preview = bodyStr.substring(0, min(400, bodyStr.length))
      call.application.log.info(
        "Webhook verified topic=$topic shopDomain=$shopDomain preview=$preview",
      )

      val shopNorm = normalizeShopDomain(shopDomain)
      val token = shopNorm?.let { tokenStore[it] }
      if (shopNorm == null || token == null) {
        call.application.log.warn(
          "Webhook: no access token for shopDomain=$shopDomain (install app / persist tokens in production)",
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
        "orders/create", "orders/updated" -> {
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
}
