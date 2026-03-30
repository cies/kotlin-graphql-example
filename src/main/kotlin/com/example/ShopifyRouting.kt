package com.example

import com.example.config.ShopifyConfig
import com.example.graphql.generated.RegisterProductWebhook
import com.example.graphql.generated.TestQuery
import com.example.shopify.AccessTokenStore
import com.example.shopify.OAuthStateStore
import com.example.shopify.ShopifySignatures
import com.example.shopify.adminGraphqlJsonUrl
import com.example.shopify.buildOAuthAuthorizeUrl
import com.example.shopify.exchangeAuthorizationCode
import com.example.shopify.normalizeShopDomain
import com.example.shopify.randomOAuthState
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

      val testResult =
        graphQLClient.execute(TestQuery()) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val edgeCount = testResult.data?.products?.edges?.size ?: 0
      log.info("TestQuery after OAuth: shop=$shop productEdges=$edgeCount")

      val callbackUrl = "${config.publicBaseUrl}/webhooks/shopify"
      val webhookRequest =
        RegisterProductWebhook(variables = RegisterProductWebhook.Variables(callbackUrl = callbackUrl))
      val wh =
        graphQLClient.execute(webhookRequest) {
          header("X-Shopify-Access-Token", oauthResponse.accessToken)
        }
      val userErrors =
        wh.data?.webhookSubscriptionCreate?.userErrors.orEmpty().joinToString { ue ->
          val f = ue.field.orEmpty().joinToString(",")
          "$f:${ue.message}"
        }
      if (!wh.errors.isNullOrEmpty()) {
        log.warn("Webhook registration GraphQL errors: ${wh.errors}")
      }
      if (userErrors.isNotEmpty()) {
        log.warn("Webhook registration userErrors: $userErrors")
      } else {
        log.info(
          "Webhook registered id=${wh.data?.webhookSubscriptionCreate?.webhookSubscription?.id}",
        )
      }

      val html =
        """
        <html><body>
        <h1>App installed</h1>
        <p>Shop: $shop</p>
        <p>TestQuery product edges: $edgeCount</p>
        <p>Webhook registration: ${if (userErrors.isEmpty()) "ok" else userErrors}</p>
        <p><a href="/demo/products?shop=$shop">/demo/products?shop=$shop</a></p>
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
      val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
      val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
      val result =
        graphQLClient.execute(TestQuery()) { header("X-Shopify-Access-Token", token) }
      val titles = result.data?.products?.edges?.mapNotNull { it.node.title }.orEmpty()
      call.respondText("Products: $titles")
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
      val preview = body.decodeToString(0, min(400, body.size))
      call.application.log.info(
        "Webhook verified topic=$topic shopDomain=$shopDomain preview=$preview",
      )
      call.respond(HttpStatusCode.OK)
    }
  }
}
