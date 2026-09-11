package dropnext.dss.handler

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.WebhookSkipReason
import kotlin.test.Test


private val acme = ShopDomain.parse("acme.myshopify.com")!!

/**
 * The log line and the response body are two renderings of one report, so both are pinned here
 * against the same outcomes; the level is pinned because it is what an alert is written against.
 */
class WebhookDeliveryReportTest {

  private fun report(outcome: WebhookMirrorOutcome, shop: ShopDomain? = acme, lagMillis: Long? = 840L) =
    WebhookDeliveryReport(
      topic = "orders/create",
      shop = shop,
      webhookId = "b54557e4-0f2d-4d7c-8c45-8d2a5c4d6f7e",
      lagMillis = lagMillis,
      tookMillis = 612L,
      outcome = outcome,
    )

  @Test
  fun `a mirrored delivery is an info line with the lag and the duration`() {
    val report = report(WebhookMirrorOutcome.Mirrored)
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.INFO)
    assert(
      report.logLine(200) ==
        "Webhook done topic=orders/create shop=acme.myshopify.com webhook_id=b54557e4-0f2d-4d7c-8c45-8d2a5c4d6f7e " +
        "outcome=mirrored lag_ms=840 took_ms=612 answered=200",
    )
    assert(report.toResponse("trace-1") == WebhookDeliveryResponse(outcome = "mirrored", traceId = "trace-1"))
  }

  @Test
  fun `a skipped delivery names its reason in both renderings`() {
    val report = report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_ADMIN_TOKEN), lagMillis = null)
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.INFO)
    assert("outcome=skipped reason=no_admin_token" in report.logLine(200))
    assert("lag_ms" !in report.logLine(200))
    assert(report.toResponse(null) == WebhookDeliveryResponse(outcome = "skipped", reason = "no_admin_token"))
  }

  @Test
  fun `a transient failure is a warning that says Shopify was asked to redeliver`() {
    val report = report(WebhookMirrorOutcome.MonolithFailed(MonolithError.Transport("connection refused")))
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.WARN)
    assert("outcome=failed transient=true error=monolith_transport" in report.logLine(502))
    assert(report.logLine(502).endsWith("answered=502"))
  }

  @Test
  fun `a permanent failure is an error, since only a human can fix it`() {
    val refused = MonolithError.Rejected(400, "bad", MonolithErrorBody(message = "bad", code = null, monolithTraceId = null))
    val report = report(WebhookMirrorOutcome.MonolithFailed(refused))
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    assert("outcome=failed transient=false error=monolith_400" in report.logLine(200))
    assert(report.toResponse("trace-2") == WebhookDeliveryResponse(outcome = "failed", error = "monolith_400", traceId = "trace-2"))
  }

  @Test
  fun `the error label names the Shopify failure class and status, never its message`() {
    val throttled = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.HttpError(429)))
    assert(throttled.errorLabel == "shopify_http_429")
    val rejected = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.TokenRejected(401)))
    assert(rejected.errorLabel == "shopify_token_rejected")
    assert(rejected.logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    val gone = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.NotFound("order 1 not found")))
    assert(gone.errorLabel == "shopify_not_found")
    assert("order 1 not found" !in gone.logLine(200))
  }

  @Test
  fun `a delivery without a shop or a webhook id still renders`() {
    val report = report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_SHOP_DOMAIN), shop = null).copy(webhookId = null)
    assert("shop=- webhook_id=- outcome=skipped reason=no_shop_domain" in report.logLine(200))
  }
}
