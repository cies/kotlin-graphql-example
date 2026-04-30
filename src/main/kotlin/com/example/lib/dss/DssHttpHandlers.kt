package com.example.lib.dss

import com.example.lib.shopify.ShopifyConfig
import com.example.lib.dss.dto.ErrorResponse
import com.example.lib.dss.dto.SyncShipmentsWithFulfillmentsPayload
import com.example.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import com.example.lib.dss.dto.TrackingUpdatePayload
import com.example.lib.dss.dto.TrackingUpdateResponse
import com.example.lib.shopify.adminGraphqlJsonUrl
import com.example.lib.shopify.normalizeShopDomain
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.net.URI

class DssHttpHandlers(
  private val shopifyConfig: ShopifyConfig,
  private val dssConfig: DssAppConfig,
  private val httpClient: HttpClient,
  private val fulfillmentService: DssFulfillmentService,
) {
  suspend fun handleSyncShipments(
    call: ApplicationCall,
    logLabel: String,
  ) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<SyncShipmentsWithFulfillmentsPayload>()
    val shop =
      normalizeShopDomain(body.shopifySubdomain)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))
    if (dssConfig.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        SyncShipmentsWithFulfillmentsResponse(newFulfillmentIds = listOf(9_000_000_000_000_001L)),
      )
    }
    val token =
      when (val t = call.resolveShopifyAdminToken(body.shopifySubdomain, dssConfig)) {
        ShopifyAdminToken.Missing ->
          return call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(
              error = "missing Shopify Admin token: use header X-Shopify-Access-Token or configure DSS_SHOP_ACCESS_TOKENS",
            ),
          )
        is ShopifyAdminToken.Resolved -> t.token
      }
    val gqlUrl = URI(adminGraphqlJsonUrl(shop, shopifyConfig.apiVersion)).toURL()
    val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
    val result =
      try {
        fulfillmentService.syncShipmentsWithFulfillments(graphQLClient, token, body)
      } catch (e: Throwable) {
        call.application.log.warn("$logLabel threw", e)
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
      }
    result.fold(
      onSuccess = { call.respond(it) },
      onFailure = { e ->
        call.application.log.warn("$logLabel failed", e)
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
      },
    )
  }

  suspend fun handleTrackingUpdate(
    call: ApplicationCall,
    logLabel: String,
  ) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<TrackingUpdatePayload>()
    val shop =
      normalizeShopDomain(body.shopifySubdomain)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "invalid shopify_subdomain"))
    if (dssConfig.sandboxFakeShopify) {
      return call.respond(
        HttpStatusCode.OK,
        TrackingUpdateResponse(fulfillmentEventId = 9_000_000_000_000_001L),
      )
    }
    val token =
      when (val t = call.resolveShopifyAdminToken(body.shopifySubdomain, dssConfig)) {
        ShopifyAdminToken.Missing ->
          return call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(
              error = "missing Shopify Admin token: use header X-Shopify-Access-Token or configure DSS_SHOP_ACCESS_TOKENS",
            ),
          )
        is ShopifyAdminToken.Resolved -> t.token
      }
    val gqlUrl = URI(adminGraphqlJsonUrl(shop, shopifyConfig.apiVersion)).toURL()
    val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
    val result =
      try {
        fulfillmentService.createTrackingEvent(graphQLClient, token, body)
      } catch (e: Throwable) {
        call.application.log.warn("$logLabel threw", e)
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
      }
    result.fold(
      onSuccess = { call.respond(it) },
      onFailure = { e ->
        call.application.log.warn("$logLabel failed", e)
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = clientErrorMessage(e)))
      },
    )
  }
}
