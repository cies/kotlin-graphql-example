package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.WebhookRegistrationFailure
import dropnext.dss.domain.WebhookSubscriptionStatus
import kotlinx.html.*
import kotlinx.html.stream.appendHTML


/** Renders the post-install confirmation page as a complete HTML document. */
fun renderOAuthInstallPage(report: ShopInstallReport): String = StringBuilder("<!DOCTYPE html>\n").appendHTML().html {
  head {
    meta { name = "robots"; content = "noindex, nofollow" }
  }
  body {
    h1 { +"App installed" }
    p { +"Shop: ${report.shop.normalizedShopifyHost} (id ${report.shopId?.value ?: "unknown"})" }
    renderMonolithPersistBlock(report.monolithPersist)
    p { +"Products on the first catalogue page: ${report.productSampleCount ?: "unknown (lookup failed)"}" }
    p {
      +"Webhook callback URL: "
      code { +report.webhookCallbackUrl }
    }
    p {
      +"Active "
      code { +"products/*" }
      +" and "
      code { +"orders/*" }
      +" webhook subscriptions:"
    }
    renderSubscriptionList(report.webhooks.activeSubscriptions)
    p { +"Webhook subscriptions added in this install:" }
    renderSubscriptionList(report.webhooks.addedSubscriptions)
    if (report.webhooks.failures.isNotEmpty()) {
      renderFailedTopics(report.webhooks.failures)
    }
  }
}.toString()

private fun FlowContent.renderMonolithPersistBlock(outcome: MonolithPersistOutcome) {
  when (outcome) {
    is MonolithPersistOutcome.Persisted -> p {
      style = "color:green"
      strong { +"Shopify token saved via monolith" }
      +" (store_id=${outcome.storeId}) and cached in memory - webhooks and routes can use this process immediately."
    }

    is MonolithPersistOutcome.Failed -> p {
      style = "color:#b91c1c"
      strong { +"Saving the token to the monolith failed" }
      +" (${outcome.httpStatus?.let { "HTTP status $it" } ?: "no response"}). Token is cached in this server's memory only."
      if (!outcome.detail.isNullOrBlank()) {
        +" Details: "
        code { +outcome.detail.take(400) }
      }
    }
  }
}

private fun FlowContent.renderSubscriptionList(subs: List<WebhookSubscriptionStatus>) {
  ul {
    if (subs.isEmpty()) {
      li { +"None" }
    } else {
      subs.forEach { sub ->
        li {
          code { +sub.topic }
          +" -> "
          code { +sub.uri }
          +" (id "
          code { +sub.id }
          +")"
        }
      }
    }
  }
}

private fun FlowContent.renderFailedTopics(failures: List<WebhookRegistrationFailure>) {
  p {
    style = "color:red"
    strong { +"Webhook registrations that failed (check app scopes in Partner Dashboard):" }
  }
  ul {
    failures.forEach { failure ->
      li {
        style = "color:red"
        code { +failure.topic }
        +" — "
        +failure.error
      }
    }
  }
  p {
    +"Make sure "
    code { +"SHOPIFY_SCOPES" }
    +" includes "
    code { +"read_orders,write_fulfillments" }
    +" and that your Shopify Partner Dashboard app has "
    +"Orders API access enabled. Reinstall the app after fixing."
  }
}
