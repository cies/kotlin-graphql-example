package dropnext.dss.handler

import dropnext.dss.GraphqlClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.dss.ShopAccessTokenCache
import dropnext.dss.lib.dss.ShopifyAdminToken
import dropnext.dss.lib.dss.clientErrorMessage
import dropnext.dss.lib.dss.dto.ErrorResponse
import dropnext.dss.lib.dss.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.shopify.FulfillmentCreateDemoBody
import dropnext.dss.shopify.FulfillmentTrackingUpdateDemoBody
import dropnext.dss.shopify.normalizeShopDomain
import dropnext.graphql.generated.FulfillmentCreateWithTracking
import dropnext.graphql.generated.FulfillmentTrackingInfoUpdateMutation
import dropnext.graphql.generated.GetOrderById
import dropnext.graphql.generated.SyncProductsPage
import dropnext.graphql.generated.inputs.FulfillmentTrackingInput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText


private val log = KotlinLogging.logger {}

/**
 * Demo / smoke-test handlers exposed only when [DssAppConfig.enableDemoRoutes] is true.
 * They are intentionally lightweight (no internal-secret check) — keep them disabled in production
 * or guard them with a reverse-proxy ACL.
 */
class DemoHandlers(
  private val dssConfig: DssAppConfig,
  private val gqlClientCache: GraphqlClientCache,
  private val httpMonolithClient: MonolithService?,
  private val shopTokens: ShopAccessTokenCache,
) {
  private val config = dssConfig.shopify

  suspend fun handleListProducts(call: ApplicationCall) {
    val rawShop = call.request.queryParameters["shop"] ?: run {
      log.warn { "[demo] GET ${DssPaths.DEMO_PRODUCTS} — missing shop query parameter" }
      return call.respondText(
        "Pass ?shop=your-store.myshopify.com",
        status = HttpStatusCode.BadRequest,
      )
    }
    val shop = normalizeShopDomain(rawShop) ?: run {
      log.warn { "[demo] GET ${DssPaths.DEMO_PRODUCTS} — invalid shop rawShop=$rawShop" }
      return call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
    }
    val token = resolveToken(shop, "GET ${DssPaths.DEMO_PRODUCTS}") ?: return call.respondText(
      "No Admin token for this shop. Set DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN (+ SANDBOX_SHOP), or complete OAuth and configure env from the success page.",
      status = HttpStatusCode.Unauthorized,
    )
    if (dssConfig.sandboxFakeShopify) {
      return call.respondText(
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
      val gqlClient = gqlClientCache.forShop(shop, config.apiVersion)
      val result = gqlClient.execute(SyncProductsPage(SyncProductsPage.Variables(first = first, after = after))) {
        header("X-Shopify-Access-Token", token)
      }
      val gqlErrors = result.errors
      if (!gqlErrors.isNullOrEmpty()) {
        log.warn { "[demo] GET ${DssPaths.DEMO_PRODUCTS} — graphql_errors shop=$shop errors=$gqlErrors" }
        call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(error = gqlErrors.joinToString { it.message }.take(1_200)),
        )
        return
      }
      val conn = result.data?.products
      val lines = conn?.edges.orEmpty().map { edge ->
        val v = edge.node.variants.edges.joinToString { ve ->
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
      log.error(e) { "[demo] GET ${DssPaths.DEMO_PRODUCTS} failed shop=$shop: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

  suspend fun handleGetOrder(call: ApplicationCall) {
    val rawShop = call.request.queryParameters["shop"] ?: run {
      log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} — missing shop query parameter" }
      return call.respondText("Pass ?shop=", status = HttpStatusCode.BadRequest)
    }
    val idParam = call.request.queryParameters["id"] ?: run {
      log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} shop=$rawShop — missing id query parameter" }
      return call.respondText(
        "Pass ?id=gid://shopify/Order/... or numeric id",
        status = HttpStatusCode.BadRequest,
      )
    }
    val shop = normalizeShopDomain(rawShop) ?: run {
      log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} — invalid_shop rawShop=$rawShop" }
      return call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
    }
    val token = resolveToken(shop, "GET ${DssPaths.DEMO_ORDER}") ?: return call.respondText(
      "No Admin token for this shop. Configure DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN.",
      status = HttpStatusCode.Unauthorized,
    )
    if (dssConfig.sandboxFakeShopify) {
      return call.respondText(
        "Order (DSS_SANDBOX_FAKE_SHOPIFY — no HTTP to Shopify):\n" +
          "Order #1001 id=$idParam shop=$shop",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    val orderGid = if (idParam.startsWith("gid://")) {
      idParam
    } else {
      val n = idParam.toLongOrNull() ?: run {
        log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} — invalid_id shop=$shop id=$idParam" }
        return call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
      }
      "gid://shopify/Order/$n"
    }
    try {
      val gqlClient = gqlClientCache.forShop(shop, config.apiVersion)
      val result = gqlClient.execute(GetOrderById(GetOrderById.Variables(orderGid))) {
        header("X-Shopify-Access-Token", token)
      }
      val o = result.data?.order
      if (o == null) {
        log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} — order_null shop=$shop orderGid=$orderGid errors=${result.errors}" }
        call.respondText("Order not found or error: ${result.errors}", status = HttpStatusCode.NotFound)
        return
      }
      val fos = o.fulfillmentOrders.edges.joinToString { e ->
        "${e.node.id} status=${e.node.status}"
      }
      call.respondText(
        "Order ${o.name} email=${o.email} financial=${o.displayFinancialStatus} fulfillment=${o.displayFulfillmentStatus}\n" +
          "Fulfillment orders: $fos\n" +
          "Line items: ${o.lineItems.edges.size}",
      )
    } catch (e: Throwable) {
      val msg = clientErrorMessage(e)
      log.error(e) { "[demo] GET ${DssPaths.DEMO_ORDER} failed shop=$shop: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

  suspend fun handleCreateFulfillment(call: ApplicationCall) {
    val body = call.receive<FulfillmentCreateDemoBody>()
    val shop = normalizeShopDomain(body.shop) ?: run {
      log.warn { "[demo] POST ${DssPaths.DEMO_FULFILLMENT_CREATE} — invalid_shop body.shop=${body.shop}" }
      return call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
    }
    val token = resolveToken(shop, "POST ${DssPaths.DEMO_FULFILLMENT_CREATE}") ?: return call.respondText(
      "No Admin token for this shop",
      status = HttpStatusCode.Unauthorized,
    )
    if (dssConfig.sandboxFakeShopify) {
      return call.respondText(
        "Fulfillment create (DSS_SANDBOX_FAKE_SHOPIFY): ok\ngid://shopify/Fulfillment/9",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    try {
      val gqlClient = gqlClientCache.forShop(shop, config.apiVersion)
      val tracking = FulfillmentTrackingInput(
        company = body.company,
        number = body.trackingNumber,
        url = body.trackingUrl,
      )
      val r = gqlClient.execute(
        FulfillmentCreateWithTracking(
          FulfillmentCreateWithTracking.Variables(
            fulfillmentOrderId = body.fulfillmentOrderId,
            tracking = tracking,
            notifyCustomer = body.notifyCustomer,
          ),
        ),
      ) { header("X-Shopify-Access-Token", token) }
      val err = r.data?.fulfillmentCreate?.userErrors.orEmpty().joinToString { "${it.field}:${it.message}" }
      if (err.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn {
          "[demo] POST ${DssPaths.DEMO_FULFILLMENT_CREATE} — user_or_graphql_errors shop=$shop userErrors=$err graphql=${r.errors}"
        }
        call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Fulfillment created id=${r.data?.fulfillmentCreate?.fulfillment?.id}")
      }
    } catch (e: Throwable) {
      val msg = clientErrorMessage(e)
      log.error(e) { "[demo] POST ${DssPaths.DEMO_FULFILLMENT_CREATE} failed shop=$shop: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

  suspend fun handleUpdateTracking(call: ApplicationCall) {
    val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
    val shop = normalizeShopDomain(body.shop) ?: run {
      log.warn { "[demo] POST ${DssPaths.DEMO_FULFILLMENT_TRACKING} — invalid_shop body.shop=${body.shop}" }
      return call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
    }
    val token = resolveToken(shop, "POST ${DssPaths.DEMO_FULFILLMENT_TRACKING}") ?: return call.respondText(
      "No Admin token for this shop",
      status = HttpStatusCode.Unauthorized,
    )
    if (dssConfig.sandboxFakeShopify) {
      return call.respondText(
        "Tracking update (DSS_SANDBOX_FAKE_SHOPIFY): ok",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    try {
      val gqlClient = gqlClientCache.forShop(shop, config.apiVersion)
      val tracking = FulfillmentTrackingInput(
        company = body.company,
        number = body.trackingNumber,
        url = body.trackingUrl,
      )
      val r = gqlClient.execute(
        FulfillmentTrackingInfoUpdateMutation(
          FulfillmentTrackingInfoUpdateMutation.Variables(
            fulfillmentId = body.fulfillmentId,
            trackingInfoInput = tracking,
            notifyCustomer = body.notifyCustomer,
          ),
        ),
      ) { header("X-Shopify-Access-Token", token) }
      val err = r.data?.fulfillmentTrackingInfoUpdate?.userErrors.orEmpty().joinToString {
        "${it.field}:${it.message}"
      }
      if (err.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn {
          "[demo] POST ${DssPaths.DEMO_FULFILLMENT_TRACKING} — user_or_graphql_errors shop=$shop userErrors=$err graphql=${r.errors}"
        }
        call.respondText("Errors: $err graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Tracking updated id=${r.data?.fulfillmentTrackingInfoUpdate?.fulfillment?.id}")
      }
    } catch (e: Throwable) {
      val msg = clientErrorMessage(e)
      log.error(e) { "[demo] POST ${DssPaths.DEMO_FULFILLMENT_TRACKING} failed shop=$shop: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

  /** Returns the resolved token, or null when missing (caller responds with 401). */
  private suspend fun resolveToken(shop: String, logLabel: String): String? =
    when (val t = shopifyAdminTokenWithMonolithFallback(shop, shopTokens, httpMonolithClient)) {
      ShopifyAdminToken.Missing -> {
        log.warn { "[demo] $logLabel — no_admin_token shop=$shop" }
        null
      }
      is ShopifyAdminToken.Resolved -> t.token
    }
}
