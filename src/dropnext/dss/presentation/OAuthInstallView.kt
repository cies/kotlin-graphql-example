package dropnext.dss.presentation

import dropnext.dss.path.DssPaths
import dropnext.dss.path.MonolithPaths
import dropnext.dss.shopify.WebhookSubscriptionStatus
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.code
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.ul

/**
 * Outcome of attempting to persist the freshly-obtained Shopify Admin token to the monolith
 * during OAuth install. The view renders one of three info / success / error notices from this.
 */
sealed interface MonolithPersistOutcome {
  data object MonolithNotConfigured : MonolithPersistOutcome
  data class Persisted(val storeId: Long) : MonolithPersistOutcome
  data class Failed(val httpStatus: Int, val detail: String?) : MonolithPersistOutcome
}

/** All data the install-success page needs. The view does not know about [io.ktor.server.application.ApplicationCall]. */
data class OAuthInstallPageModel(
  val shop: String,
  val shopId: Long,
  val monolithPersist: MonolithPersistOutcome,
  val productEdgeCount: Int,
  val webhookCallbackUrl: String,
  val activeSubscriptions: List<WebhookSubscriptionStatus>,
  val addedSubscriptions: List<WebhookSubscriptionStatus>,
  val failedTopics: List<Pair<WebhookSubscriptionTopic, String>>,
)

/** Renders the post-install confirmation page as a complete HTML document. */
fun renderOAuthInstallPage(model: OAuthInstallPageModel): String {
  val builder = StringBuilder("<!DOCTYPE html>\n")
  builder.appendHTML().html {
    head {
      meta { name = "robots"; content = "noindex, nofollow" }
    }
    body {
      h1 { +"App installed" }
      p { +"Shop: ${model.shop} (id ${model.shopId})" }
      renderMonolithPersistBlock(model.monolithPersist)
      p { +"SyncProductsPage (first 3) product edges: ${model.productEdgeCount}" }
      p {
        +"Webhook callback URL: "
        code { +model.webhookCallbackUrl }
      }
      p {
        +"Active "
        code { +"products/*" }
        +" and "
        code { +"orders/*" }
        +" webhook subscriptions:"
      }
      renderSubscriptionList(model.activeSubscriptions)
      p { +"Webhook subscriptions added in this install:" }
      renderSubscriptionList(model.addedSubscriptions)
      if (model.failedTopics.isNotEmpty()) {
        renderFailedTopics(model.failedTopics)
      }
      val demoProductsHref = "${DssPaths.DEMO_PRODUCTS}?shop=${model.shop}"
      val demoOrderHref = "${DssPaths.DEMO_ORDER}?shop=${model.shop}&id=ORDER_GID"
      p { a(href = demoProductsHref) { +demoProductsHref } }
      p { a(href = demoOrderHref) { +"${DssPaths.DEMO_ORDER}?shop=${model.shop}&id=..." } }
    }
  }
  return builder.toString()
}

private fun kotlinx.html.FlowContent.renderMonolithPersistBlock(
  outcome: MonolithPersistOutcome,
) {
  when (outcome) {
    is MonolithPersistOutcome.MonolithNotConfigured -> {
      p {
        style = "color:#b45309"
        strong { +"Monolith not configured:" }
        +" "
        code { +"MONOLITH_BASE_URL" }
        +" is unset, so we did not "
        code { +"PUT …${MonolithPaths.STORES_API_KEY}" }
        +". The Shopify Admin token is cached in this server’s memory only."
      }
      p {
        +"Set "
        code { +"MONOLITH_BASE_URL" }
        +" (and normally "
        code { +"MONOLITH_API_KEY" }
        +" for Bearer auth to the monolith) in prod and dev if installs should persist the token DropNext-wide."
      }
    }
    is MonolithPersistOutcome.Persisted -> p {
      style = "color:green"
      strong { +"Shopify token saved via monolith" }
      +" ("
      code { +"PUT …${MonolithPaths.STORES_API_KEY}" }
      +", store_id=${outcome.storeId}) and cached in memory — webhooks and routes can use this process immediately."
    }
    is MonolithPersistOutcome.Failed -> p {
      style = "color:#b91c1c"
      strong {
        +"Monolith "
        code { +"PUT …${MonolithPaths.STORES_API_KEY}" }
        +" failed"
      }
      +" (HTTP status ${outcome.httpStatus}). Token is cached in this server’s memory only."
      if (!outcome.detail.isNullOrBlank()) {
        +" Details: "
        code { +outcome.detail.take(400) }
      }
    }
  }
}

private fun kotlinx.html.FlowContent.renderSubscriptionList(subs: List<WebhookSubscriptionStatus>) {
  ul {
    if (subs.isEmpty()) {
      li { +"None" }
    } else {
      for (sub in subs) {
        li {
          code { +sub.topic.name }
          +" → "
          code { +sub.uri }
          +" (id "
          code { +sub.id }
          +")"
        }
      }
    }
  }
}

private fun kotlinx.html.FlowContent.renderFailedTopics(failures: List<Pair<WebhookSubscriptionTopic, String>>) {
  p {
    style = "color:red"
    strong { +"Webhook registrations that failed (check app scopes in Partner Dashboard):" }
  }
  ul {
    for ((topic, errorMsg) in failures) {
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
