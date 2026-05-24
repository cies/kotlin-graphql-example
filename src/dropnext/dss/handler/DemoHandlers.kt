package dropnext.dss.handler

import dropnext.dss.config.Config
import dropnext.dss.lib.dto.ErrorResponse
import dropnext.dss.lib.ktor.clientErrorMessage
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.monolith.ShopifyGraphqlServiceFactory
import dropnext.dss.path.Paths
import dropnext.dss.shopify.FulfillmentCreateDemoBody
import dropnext.dss.shopify.FulfillmentTrackingUpdateDemoBody
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.orderGidFromParam
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText


private val log = KotlinLogging.logger {}

private const val MISSING_TOKEN_HINT =
  "No Admin token for this shop. Configure DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN (+ SANDBOX_SHOP), " +
    "or complete OAuth and copy the env line shown on the install success page."

/**
 * Demo / smoke-test handlers exposed only when [dropnext.dss.config.DevConfig.enableDemoRoutes] is true.
 * They are intentionally lightweight (no internal-secret check) — keep them disabled in production
 * or guard them with a reverse-proxy ACL.
 */
class DemoHandlers(
  private val dssConfig: Config,
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
) {
  suspend fun handleListProducts(call: ApplicationCall) {
    val ctx = prepareDemo(call, call.request.queryParameters["shop"], "GET ${Paths.demoProducts}") ?: return
    val first = call.request.queryParameters["first"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
    val after = call.request.queryParameters["after"]
    runDemo(call, ctx) {
      val result = ctx.shopify.syncProductsPage(first, after)
      if (!result.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — graphql_errors shop=${ctx.shopify.shop.host} errors=${result.errors}" }
        call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(error = result.errors.orEmpty().joinToString { it.message }.take(1_200)),
        )
        return@runDemo
      }
      val conn = result.data?.products
      val lines = conn?.edges.orEmpty().map { edge ->
        val variants = edge.node.variants.edges.joinToString { ve -> "${ve.node.sku ?: "-"}@${ve.node.price}" }
        "${edge.node.title} [variants: $variants]"
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
  }

  suspend fun handleGetOrder(call: ApplicationCall) {
    val idParam = call.request.queryParameters["id"] ?: run {
      log.warn { "[demo] GET ${Paths.demoOrder} — missing id query parameter" }
      return call.respondText("Pass ?id=gid://shopify/Order/... or numeric id", status = HttpStatusCode.BadRequest)
    }
    val ctx = prepareDemo(call, call.request.queryParameters["shop"], "GET ${Paths.demoOrder}") ?: return
    val orderGid = orderGidFromParam(idParam) ?: run {
      log.warn { "[demo] ${ctx.routeLabel} — invalid_id shop=${ctx.shopify.shop.host} id=$idParam" }
      return call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
    }
    runDemo(call, ctx) {
      val result = ctx.shopify.getOrderById(orderGid)
      val order = result.data?.order
      if (order == null) {
        log.warn { "[demo] ${ctx.routeLabel} — order_null shop=${ctx.shopify.shop.host} orderGid=$orderGid errors=${result.errors}" }
        return@runDemo call.respondText("Order not found or error: ${result.errors}", status = HttpStatusCode.NotFound)
      }
      val fos = order.fulfillmentOrders.edges.joinToString { e -> "${e.node.id} status=${e.node.status}" }
      call.respondText(
        "Order ${order.name} email=${order.email} financial=${order.displayFinancialStatus} " +
          "fulfillment=${order.displayFulfillmentStatus}\n" +
          "Fulfillment orders: $fos\n" +
          "Line items: ${order.lineItems.edges.size}",
      )
    }
  }

  suspend fun handleCreateFulfillment(call: ApplicationCall) {
    val body = call.receive<FulfillmentCreateDemoBody>()
    val ctx = prepareDemo(call, body.shop, "POST ${Paths.demoFulfillmentCreate}") ?: return
    runDemo(call, ctx) {
      val r = ctx.shopify.demoCreateFulfillmentWithTracking(
        fulfillmentOrderId = body.fulfillmentOrderId,
        company = body.company,
        trackingNumber = body.trackingNumber,
        trackingUrl = body.trackingUrl,
        notifyCustomer = body.notifyCustomer,
      )
      val userErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty().joinToString { "${it.field}:${it.message}" }
      if (userErrs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — user_or_graphql_errors shop=${ctx.shopify.shop.host} userErrors=$userErrs graphql=${r.errors}" }
        call.respondText("Errors: $userErrs graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Fulfillment created id=${r.data?.fulfillmentCreate?.fulfillment?.id}")
      }
    }
  }

  suspend fun handleUpdateTracking(call: ApplicationCall) {
    val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
    val ctx = prepareDemo(call, body.shop, "POST ${Paths.demoFulfillmentTracking}") ?: return
    runDemo(call, ctx) {
      val r = ctx.shopify.demoUpdateFulfillmentTracking(
        fulfillmentId = body.fulfillmentId,
        company = body.company,
        trackingNumber = body.trackingNumber,
        trackingUrl = body.trackingUrl,
        notifyCustomer = body.notifyCustomer,
      )
      val userErrs = r.data?.fulfillmentTrackingInfoUpdate?.userErrors.orEmpty()
        .joinToString { "${it.field}:${it.message}" }
      if (userErrs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — user_or_graphql_errors shop=${ctx.shopify.shop.host} userErrors=$userErrs graphql=${r.errors}" }
        call.respondText("Errors: $userErrs graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Tracking updated id=${r.data?.fulfillmentTrackingInfoUpdate?.fulfillment?.id}")
      }
    }
  }

  /** Per-call context after shop validation + token resolution; pre-built [ShopifyGraphqlService] for the demo flow. */
  private data class DemoContext(
    val shopify: ShopifyGraphqlService,
    val routeLabel: String,
  )

  /**
   * Validates `?shop=` / body shop, builds a [ShopifyGraphqlService] (cache → monolith fallback), and
   * returns a [DemoContext] ready for a Graphql call. Returns `null` after responding with 400/401
   * so the caller short-circuits with `?: return`.
   */
  private suspend fun prepareDemo(
    call: ApplicationCall,
    rawShop: String?,
    routeLabel: String,
  ): DemoContext? {
    if (rawShop == null) {
      log.warn { "[demo] $routeLabel — missing shop parameter" }
      call.respondText("Pass ?shop=your-store.myshopify.com", status = HttpStatusCode.BadRequest)
      return null
    }
    val shop = ShopDomain.parse(rawShop) ?: run {
      log.warn { "[demo] $routeLabel — invalid_shop rawShop=$rawShop" }
      call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
      return null
    }
    val shopify = shopifyGraphqlServiceFactory.forShop(shop) ?: run {
      log.warn { "[demo] $routeLabel — no_admin_token shop=${shop.host}" }
      call.respondText(MISSING_TOKEN_HINT, status = HttpStatusCode.Unauthorized)
      return null
    }
    return DemoContext(shopify, routeLabel)
  }

  /** Wraps the per-handler Graphql block with the shared try/catch → 400 ErrorResponse handler. */
  private suspend inline fun runDemo(
    call: ApplicationCall,
    ctx: DemoContext,
    block: () -> Unit,
  ) {
    try {
      block()
    } catch (e: Exception) {
      val msg = clientErrorMessage(e)
      log.error(e) { "[demo] ${ctx.routeLabel} failed shop=${ctx.shopify.shop.host}: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

}
