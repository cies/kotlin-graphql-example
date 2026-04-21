package com.example.dss

import com.example.dss.dto.DeleteProductVariantsRequest
import com.example.dss.dto.DeleteProductVariantsResponse
import com.example.dss.dto.ErrorResponse
import com.example.dss.dto.SyncShipmentsWithFulfillmentsPayload
import com.example.dss.dto.TrackingUpdatePayload
import com.example.dss.dto.UpdateStoreApiKeyRequest
import com.example.dss.dto.UpdateStoreApiKeyResponse
import com.example.dss.dto.UpsertProductVariantsRequest
import com.example.dss.dto.UpsertProductVariantsResponse
import com.example.dss.persistence.FileStoreRepository
import com.example.config.ShopifyConfig
import com.example.shopify.adminGraphqlJsonUrl
import com.example.shopify.normalizeShopDomain
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.net.URI

/**
 * HTTP routes our **main backend** calls: stores, variant lists, shipment sync, and tracking.
 * Paths and JSON match [docs/openapi/dss-api.yaml] so tools and humans stay in sync.
 */
fun Application.configureDssRoutes(
  config: ShopifyConfig,
  dssConfig: DssAppConfig,
  storeRepo: FileStoreRepository,
  httpClient: HttpClient,
  fulfillmentService: DssFulfillmentService,
) {
  routing {
    get("/stores") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@get
      val raw =
        call.request.queryParameters["shopify_subdomain"]
          ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing shopify_subdomain"))
      val shop =
        normalizeShopDomain(raw)
          ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      val rec =
        storeRepo.findBySubdomain(shop)
          ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
      call.respond(
        com.example.dss.dto.StoreResponse(
          store_id = rec.id,
          shopify_shop_id = rec.shopifyShopId,
          api_key = rec.accessToken,
        ),
      )
    }

    put("/stores/api-key") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@put
      val body = call.receive<UpdateStoreApiKeyRequest>()
      val shop =
        normalizeShopDomain(body.shopify_subdomain)
          ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      val putResult = storeRepo.updateApiKeyForExistingStoreOnly(shop, body.shopify_shop_id, body.api_key)
      val rec =
        putResult.getOrNull()
          ?: return@put call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse("Store not found or belongs to a different shop."),
          )
      call.respond(UpdateStoreApiKeyResponse(store_id = rec.id))
    }

    /** Backward-compatible alias; prefer `PUT /stores/api-key` (OpenAPI). */
    put("/stores") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@put
      val body = call.receive<UpdateStoreApiKeyRequest>()
      val shop =
        normalizeShopDomain(body.shopify_subdomain)
          ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      val putResult = storeRepo.updateApiKeyForExistingStoreOnly(shop, body.shopify_shop_id, body.api_key)
      val rec =
        putResult.getOrNull()
          ?: return@put call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse("Store not found or belongs to a different shop."),
          )
      call.respond(UpdateStoreApiKeyResponse(store_id = rec.id))
    }

    get("/product-variants") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@get
      val raw =
        call.request.queryParameters["shopify_subdomain"]
          ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing shopify_subdomain"))
      val shop =
        normalizeShopDomain(raw)
          ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      val rec =
        storeRepo.findBySubdomain(shop)
          ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
      call.respond(com.example.dss.dto.VariantIdsResponse(product_variant_ids = rec.productVariantIds.toList().sorted()))
    }

    post("/product-variants") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@post
      val body = call.receive<UpsertProductVariantsRequest>()
      val shop =
        normalizeShopDomain(body.shopify_subdomain)
          ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      if (storeRepo.findBySubdomain(shop) == null) {
        return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
      }
      val ids = body.product_variants.map { it.product_variant_id }
      storeRepo.mergeVariantIds(shop, ids)
      call.respond(UpsertProductVariantsResponse(upserted = ids.size))
    }

    delete("/product-variants") {
      if (!call.requireDssInternalSecret(dssConfig.dssInternalSecret)) return@delete
      val body = call.receive<DeleteProductVariantsRequest>()
      val shop =
        normalizeShopDomain(body.shopify_subdomain)
          ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
      if (storeRepo.findBySubdomain(shop) == null) {
        return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
      }
      storeRepo.removeVariantIds(shop, body.product_variant_ids)
      call.respond(DeleteProductVariantsResponse(deleted = body.product_variant_ids.size))
    }

    post("/sync-shipments-with-fulfillments") {
      call.handleSyncShipments(config, dssConfig, storeRepo, httpClient, fulfillmentService, "sync-shipments")
    }

    post("/tracking-updates") {
      call.handleTrackingUpdate(config, dssConfig, storeRepo, httpClient, fulfillmentService, "tracking-updates")
    }

    /** OpenAPI `webhooks.tracking-update` naming (hyphen, singular). */
    post("/tracking-update") {
      call.handleTrackingUpdate(config, dssConfig, storeRepo, httpClient, fulfillmentService, "tracking-update")
    }

    /**
     * Per attached OpenAPI: **dummy1** = tracking payload (http4k#1516 workaround).
     * Must match `postDummy1` / `TrackingUpdatePayload`.
     */
    post("/dummy1") {
      call.handleTrackingUpdate(config, dssConfig, storeRepo, httpClient, fulfillmentService, "dummy1")
    }

    /**
     * Per attached OpenAPI: **dummy2** = sync shipments payload.
     * Must match `postDummy2` / `SyncShipmentsWithFulfillmentsPayload`.
     */
    post("/dummy2") {
      call.handleSyncShipments(config, dssConfig, storeRepo, httpClient, fulfillmentService, "dummy2")
    }
  }
}

private suspend fun ApplicationCall.handleSyncShipments(
  config: ShopifyConfig,
  dssConfig: DssAppConfig,
  storeRepo: FileStoreRepository,
  httpClient: HttpClient,
  fulfillmentService: DssFulfillmentService,
  logLabel: String,
) {
  if (!requireDssInternalSecret(dssConfig.dssInternalSecret)) return
  val body = receive<SyncShipmentsWithFulfillmentsPayload>()
  val shop =
    normalizeShopDomain(body.shopify_subdomain)
      ?: return respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
  val token =
    storeRepo.getAccessToken(shop)
      ?: return respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
  val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
  val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
  val result =
    fulfillmentService.syncShipmentsWithFulfillments(graphQLClient, token, body)
  result.fold(
    onSuccess = { respond(it) },
    onFailure = { e ->
      application.log.warn("$logLabel failed", e)
      respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "sync failed"))
    },
  )
}

private suspend fun ApplicationCall.handleTrackingUpdate(
  config: ShopifyConfig,
  dssConfig: DssAppConfig,
  storeRepo: FileStoreRepository,
  httpClient: HttpClient,
  fulfillmentService: DssFulfillmentService,
  logLabel: String,
) {
  if (!requireDssInternalSecret(dssConfig.dssInternalSecret)) return
  val body = receive<TrackingUpdatePayload>()
  val shop =
    normalizeShopDomain(body.shopify_subdomain)
      ?: return respond(HttpStatusCode.BadRequest, ErrorResponse("invalid shopify_subdomain"))
  val token =
    storeRepo.getAccessToken(shop)
      ?: return respond(HttpStatusCode.NotFound, ErrorResponse("Store not found."))
  val gqlUrl = URI(adminGraphqlJsonUrl(shop, config.apiVersion)).toURL()
  val graphQLClient = GraphQLKtorClient(gqlUrl, httpClient)
  val result =
    fulfillmentService.createTrackingEvent(graphQLClient, token, body)
  result.fold(
    onSuccess = { respond(it) },
    onFailure = { e ->
      application.log.warn("$logLabel failed", e)
      respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "tracking failed"))
    },
  )
}
