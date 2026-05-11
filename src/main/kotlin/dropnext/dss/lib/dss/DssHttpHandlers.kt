package dropnext.dss.lib.dss

import dropnext.dss.config.ShopifyConfig
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateResponse
import dropnext.dss.shopify.adminGraphqlJsonUrl
import dropnext.dss.shopify.normalizeShopDomain
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class DssHttpHandlers(
  private val shopifyConfig: ShopifyConfig,
  private val dssConfig: DssAppConfig,
  private val httpClient: HttpClient,
  private val fulfillmentService: DssFulfillmentService,
) {
  private val gqlClientCache = ConcurrentHashMap<String, GraphQLKtorClient>()

  private fun graphQLClientForShop(shop: String): GraphQLKtorClient =
    gqlClientCache.getOrPut("$shop/${shopifyConfig.apiVersion}") {
      GraphQLKtorClient(URI(adminGraphqlJsonUrl(shop, shopifyConfig.apiVersion)).toURL(), httpClient)
    }

  suspend fun handleSyncShipments(
    call: ApplicationCall,
    logLabel: String,
  ) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<SyncShipmentsWithFulfillmentsRequest>()
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
    val graphQLClient = graphQLClientForShop(shop)
    when (val result = fulfillmentService.syncShipmentsWithFulfillments(graphQLClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val msg = result.toMessage()
        call.application.log.warn("$logLabel failed: $msg")
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
      }
    }
  }

  suspend fun handleTrackingUpdate(
    call: ApplicationCall,
    logLabel: String,
  ) {
    if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return
    val body = call.receive<TrackingUpdateRequest>()
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
    val graphQLClient = graphQLClientForShop(shop)
    when (val result = fulfillmentService.createTrackingEvent(graphQLClient, token, body)) {
      is FulfillmentResult.Ok -> call.respond(result.value)
      is FulfillmentResult.Err -> {
        val msg = result.toMessage()
        call.application.log.warn("$logLabel failed: $msg")
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
      }
    }
  }
}
