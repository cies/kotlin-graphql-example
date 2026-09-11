package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.token.ShopTokenStore
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Everything that happens after the OAuth code exchange handed us a shop's Admin token: learn the
 * shop's canonical domain and id, remember the token under that domain, persist it to the
 * monolith, sample the catalogue as a smoke test, and register the webhook subscriptions.
 *
 * Nothing here fails the installation: each step that cannot be completed is reported on the
 * confirmation page instead, because the token is already ours and a merchant who sees an error
 * page would only reinstall.
 */
suspend fun installShop(
  shopify: ShopifyGraphqlService,
  monolith: MonolithService,
  tokens: ShopTokenStore,
  token: ShopifyAdminToken,
  webhookCallbackUrl: String,
): ShopInstallReport {
  val identity = when (val loaded = shopify.shopIdentity()) {
    is Success -> loaded.value
    is Failure -> {
      log.warn { "Shop identity lookup failed shop=${shopify.shop.normalizedShopifyHost}: ${loaded.reason.message}" }
      null
    }
  }
  val domain = identity?.domain ?: shopify.shop
  tokens.remember(domain, token)
  log.info { "OAuth token cached in memory for shop=${domain.normalizedShopifyHost}" }

  val monolithPersist = persistTokenToMonolith(monolith, domain, identity?.shopId, token)

  val productSampleCount = when (val sampled = shopify.productSampleCount(first = 3)) {
    is Success -> sampled.value.also { log.info { "Product sample after OAuth: shop=${domain.normalizedShopifyHost} products=$it" } }
    is Failure -> {
      log.warn { "Product sample after OAuth failed shop=${domain.normalizedShopifyHost}: ${sampled.reason.message}" }
      null
    }
  }

  return ShopInstallReport(
    shop = domain,
    shopId = identity?.shopId,
    monolithPersist = monolithPersist,
    productSampleCount = productSampleCount,
    webhookCallbackUrl = webhookCallbackUrl,
    webhooks = registerShopifyWebhooks(shopify, webhookCallbackUrl),
  )
}

/** Persists the freshly obtained Shopify Admin token to the monolith; answers what the page renders. */
suspend fun persistTokenToMonolith(
  monolith: MonolithService,
  shop: ShopDomain,
  shopId: ShopifyShopId?,
  token: ShopifyAdminToken,
): MonolithPersistOutcome {
  val request = UpdateStoreApiKeyRequest(
    shopifySubdomain = shop.subdomainOnly,
    // Null when the identity lookup failed: the monolith keeps the id it has. Zero used to stand in for
    // this and locked a fresh store to shop id zero, so every later, real install of it was a mismatch.
    shopifyShopId = shopId?.value,
    apiKey = token.value,
  )
  return when (val result = monolith.putStoreApiKey(request)) {
    is Success -> {
      log.info { "Monolith store api-key updated storeId=${result.value} shop=${shop.normalizedShopifyHost}" }
      MonolithPersistOutcome.Persisted(storeId = result.value)
    }

    is Failure -> {
      logMonolithFailure("putStoreApiKey", result.reason, "shop=${shop.normalizedShopifyHost}")
      when (val error = result.reason) {
        is MonolithError.Rejected -> MonolithPersistOutcome.Failed(httpStatus = error.status, detail = error.body.message)
        is MonolithError.Undecodable -> MonolithPersistOutcome.Failed(httpStatus = error.status, detail = error.message)
        is MonolithError.Transport -> MonolithPersistOutcome.Failed(httpStatus = null, detail = error.message)
      }
    }
  }
}
