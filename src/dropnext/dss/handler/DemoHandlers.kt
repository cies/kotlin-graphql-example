package dropnext.dss.handler

import dropnext.dss.lib.shopify.graphql.GraphqlClientCache
import dropnext.dss.config.DssAppConfig
import dropnext.dss.path.DssPaths
import dropnext.dss.lib.monolith.ShopAccessTokenCache
import dropnext.dss.lib.ktor.clientErrorMessage
import dropnext.dss.lib.dto.ErrorResponse
import dropnext.dss.lib.monolith.shopifyAdminTokenWithMonolithFallback
import dropnext.dss.lib.monolith.tokenOrNull
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.shopify.FulfillmentCreateDemoBody
import dropnext.dss.shopify.FulfillmentTrackingUpdateDemoBody
import dropnext.dss.shopify.normalizeShopDomain
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
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

private const val MISSING_TOKEN_HINT =
  "No Admin token for this shop. Configure DSS_SHOP_ACCESS_TOKENS or SANDBOX_ACCESS_TOKEN (+ SANDBOX_SHOP), " +
    "or complete OAuth and copy the env line shown on the install success page."

/**
 * Demo / smoke-test handlers exposed only when [dropnext.dss.config.DevConfig.enableDemoRoutes] is true.
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
    val ctx = prepareDemo(call, call.request.queryParameters["shop"], "GET ${DssPaths.DEMO_PRODUCTS}") ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
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
    runDemo(call, ctx) {
      val result = ctx.gqlClient.execute(
        SyncProductsPage(SyncProductsPage.Variables(first = first, after = after)),
      ) { header("X-Shopify-Access-Token", ctx.token) }
      if (!result.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — graphql_errors shop=${ctx.shop} errors=${result.errors}" }
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
      log.warn { "[demo] GET ${DssPaths.DEMO_ORDER} — missing id query parameter" }
      return call.respondText("Pass ?id=gid://shopify/Order/... or numeric id", status = HttpStatusCode.BadRequest)
    }
    val ctx = prepareDemo(call, call.request.queryParameters["shop"], "GET ${DssPaths.DEMO_ORDER}") ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
      return call.respondText(
        "Order (DSS_SANDBOX_FAKE_SHOPIFY — no HTTP to Shopify):\nOrder #1001 id=$idParam shop=${ctx.shop}",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    val orderGid = orderGidFromParam(idParam) ?: run {
      log.warn { "[demo] ${ctx.routeLabel} — invalid_id shop=${ctx.shop} id=$idParam" }
      return call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
    }
    runDemo(call, ctx) {
      val result = ctx.gqlClient.execute(GetOrderById(GetOrderById.Variables(orderGid))) {
        header("X-Shopify-Access-Token", ctx.token)
      }
      val order = result.data?.order
      if (order == null) {
        log.warn { "[demo] ${ctx.routeLabel} — order_null shop=${ctx.shop} orderGid=$orderGid errors=${result.errors}" }
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
    val ctx = prepareDemo(call, body.shop, "POST ${DssPaths.DEMO_FULFILLMENT_CREATE}") ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
      return call.respondText(
        "Fulfillment create (DSS_SANDBOX_FAKE_SHOPIFY): ok\ngid://shopify/Fulfillment/9",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    runDemo(call, ctx) {
      val r = ctx.gqlClient.execute(
        FulfillmentCreateWithTracking(
          FulfillmentCreateWithTracking.Variables(
            fulfillmentOrderId = body.fulfillmentOrderId,
            tracking = body.toTrackingInput(),
            notifyCustomer = body.notifyCustomer,
          ),
        ),
      ) { header("X-Shopify-Access-Token", ctx.token) }
      val userErrs = r.data?.fulfillmentCreate?.userErrors.orEmpty().joinToString { "${it.field}:${it.message}" }
      if (userErrs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — user_or_graphql_errors shop=${ctx.shop} userErrors=$userErrs graphql=${r.errors}" }
        call.respondText("Errors: $userErrs graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Fulfillment created id=${r.data?.fulfillmentCreate?.fulfillment?.id}")
      }
    }
  }

  suspend fun handleUpdateTracking(call: ApplicationCall) {
    val body = call.receive<FulfillmentTrackingUpdateDemoBody>()
    val ctx = prepareDemo(call, body.shop, "POST ${DssPaths.DEMO_FULFILLMENT_TRACKING}") ?: return
    if (dssConfig.dev.sandboxFakeShopify) {
      return call.respondText(
        "Tracking update (DSS_SANDBOX_FAKE_SHOPIFY): ok",
        ContentType.Text.Plain,
        HttpStatusCode.OK,
      )
    }
    runDemo(call, ctx) {
      val r = ctx.gqlClient.execute(
        FulfillmentTrackingInfoUpdateMutation(
          FulfillmentTrackingInfoUpdateMutation.Variables(
            fulfillmentId = body.fulfillmentId,
            trackingInfoInput = body.toTrackingInput(),
            notifyCustomer = body.notifyCustomer,
          ),
        ),
      ) { header("X-Shopify-Access-Token", ctx.token) }
      val userErrs = r.data?.fulfillmentTrackingInfoUpdate?.userErrors.orEmpty()
        .joinToString { "${it.field}:${it.message}" }
      if (userErrs.isNotEmpty() || !r.errors.isNullOrEmpty()) {
        log.warn { "[demo] ${ctx.routeLabel} — user_or_graphql_errors shop=${ctx.shop} userErrors=$userErrs graphql=${r.errors}" }
        call.respondText("Errors: $userErrs graphql=${r.errors}", status = HttpStatusCode.BadRequest)
      } else {
        call.respondText("Tracking updated id=${r.data?.fulfillmentTrackingInfoUpdate?.fulfillment?.id}")
      }
    }
  }

  /** Inputs every demo handler needs after shop validation, token lookup, and Graphql client selection. */
  private data class DemoContext(
    val shop: String,
    val token: String,
    val gqlClient: GraphQLKtorClient,
    val routeLabel: String,
  )

  /**
   * Validates `?shop=` / body shop, resolves a Shopify Admin token (cache → optional monolith fallback),
   * and returns a [DemoContext] ready for a Graphql call. Returns `null` after responding with 400/401
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
    val shop = normalizeShopDomain(rawShop) ?: run {
      log.warn { "[demo] $routeLabel — invalid_shop rawShop=$rawShop" }
      call.respondText("Invalid shop", status = HttpStatusCode.BadRequest)
      return null
    }
    val token = shopifyAdminTokenWithMonolithFallback(shop, shopTokens, httpMonolithClient).tokenOrNull ?: run {
      log.warn { "[demo] $routeLabel — no_admin_token shop=$shop" }
      call.respondText(MISSING_TOKEN_HINT, status = HttpStatusCode.Unauthorized)
      return null
    }
    return DemoContext(shop, token, gqlClientCache.forShop(shop, config.apiVersion), routeLabel)
  }

  /** Wraps the per-handler Graphql block with the shared try/catch → 400 ErrorResponse handler. */
  private suspend inline fun runDemo(
    call: ApplicationCall,
    ctx: DemoContext,
    block: () -> Unit,
  ) {
    try {
      block()
    } catch (e: Throwable) {
      val msg = clientErrorMessage(e)
      log.error(e) { "[demo] ${ctx.routeLabel} failed shop=${ctx.shop}: $msg" }
      call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = msg))
    }
  }

  private fun orderGidFromParam(idParam: String): String? {
    if (idParam.startsWith("gid://")) return idParam
    val n = idParam.toLongOrNull() ?: return null
    return "gid://shopify/Order/$n"
  }
}


private fun FulfillmentCreateDemoBody.toTrackingInput(): FulfillmentTrackingInput =
  FulfillmentTrackingInput(company = company, number = trackingNumber, url = trackingUrl)

private fun FulfillmentTrackingUpdateDemoBody.toTrackingInput(): FulfillmentTrackingInput =
  FulfillmentTrackingInput(company = company, number = trackingNumber, url = trackingUrl)
