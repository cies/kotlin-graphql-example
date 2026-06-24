package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.lib.shopify.graphql.webhookregistration.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import kotlinx.html.*
import kotlinx.html.stream.appendHTML


/** Renders the post-install confirmation page as a complete HTML document. */
fun renderOAuthInstallPage(
  shop: String,
  shopId: Long,
  monolithPersist: MonolithPersistOutcome,
  productEdgeCount: Int,
  webhookCallbackUrl: String,
  activeSubscriptions: List<WebhookSubscriptionStatus>,
  addedSubscriptions: List<WebhookSubscriptionStatus>,
  failedTopics: List<Pair<WebhookSubscriptionTopic, String>>,
): String = StringBuilder("<!DOCTYPE html>\n").appendHTML().html {
  head {
    meta { name = "robots"; content = "noindex, nofollow" }
  }
  body {
    h1 { +"App installed" }
    p { +"Shop: $shop (id $shopId)" }
    renderMonolithPersistBlock(monolithPersist)
    p { +"SyncProductsPage (first 3) product edges: $productEdgeCount" }
    p {
      +"Webhook callback URL: "
      code { +webhookCallbackUrl }
    }
    p {
      +"Active "
      code { +"products/*" }
      +" and "
      code { +"orders/*" }
      +" webhook subscriptions:"
    }
    renderSubscriptionList(activeSubscriptions)
    p { +"Webhook subscriptions added in this install:" }
    renderSubscriptionList(addedSubscriptions)
    if (failedTopics.isNotEmpty()) {
      renderFailedTopics(failedTopics)
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
      strong {
        +"Monolith PUT /orders failed"
      }
      +" (HTTP status ${outcome.httpStatus}). Token is cached in this server's memory only."
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
          code { +sub.topic.name }
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

private fun FlowContent.renderFailedTopics(failures: List<Pair<WebhookSubscriptionTopic, String>>) {
  p {
    style = "color:red"
    strong { +"Webhook registrations that failed (check app scopes in Partner Dashboard):" }
  }
  ul {
    failures.forEach { (topic, errorMsg) ->
      li {
        style = "color:red"
        code { +topic.name }
        +" — "
        +errorMsg
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
