package dropnext.dss.lib.monolith

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import io.ktor.client.HttpClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap


/**
 * Builds [dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService] instances on demand and hides the token resolution chain (header →
 * in-memory cache → monolith fallback) that handlers previously had to thread through every call.
 *
 * Token resolution is fast-pathed: the in-memory [ShopAccessTokenCache] is checked first; only on
 * miss do we make the network call to the monolith, and on success we backfill the cache so the
 * next request avoids the round trip. Constant-time identity / privacy: tokens themselves are
 * never logged, only the resolved shop subdomain.
 */
class ShopifyServiceFactory(
  httpClient: HttpClient,
  private val tokens: ShopAccessTokenCache,
  private val monolith: MonolithService,
  private val apiVersion: String,
) {
  private val gqlClientCache = GraphqlClientCache(httpClient)

  /**
   * Returns a [dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService] for [shop], or `null` when no Admin token is resolvable. When
   * [explicitToken] is supplied (e.g.: directly after an OAuth code exchange, before the token
   * has been cached) it short-circuits both the cache and the monolith fallback.
   */
  suspend fun forShop(shop: ShopDomain, explicitToken: String? = null): ShopifyGraphqlService? {
    val token = explicitToken ?: resolveToken(shop) ?: return null
    val gqlClient = gqlClientCache.forShop(shop, apiVersion)
    return ShopifyGraphqlService(shop, gqlClient, token)
  }

  private suspend fun resolveToken(shop: ShopDomain): String? {
    tokens[shop]?.let { return it }
    return when (val result = monolith.getStore(shop.subdomainShort)) {
      is GetStoreResult.Ok -> result.apiKey?.also { tokens[shop] = it }
      is GetStoreResult.NotFound -> null
      is GetStoreResult.Error -> {
        logMonolithFailure(
          "getStore",
          result.status,
          result.parsed,
          "subdomain=${shop.subdomainShort}"
        )
        null
      }
    }
  }
}

/**
 * Caches [com.expediagroup.graphql.client.ktor.GraphQLKtorClient] instances keyed by shop + API version so a new client object is not
 * allocated on every request. The underlying [httpClient] is shared (one OkHttp connection
 * pool), so the only thing actually cached is the per-shop URL binding plus a small wrapper.
 *
 * Callers get a client with [forShop] and the per-shop Admin token is passed by [ShopifyGraphqlService]
 * via the `X-Shopify-Access-Token` header — tokens are never baked into a cached client.
 */
private class GraphqlClientCache(private val httpClient: HttpClient) {
  private val cache = ConcurrentHashMap<String, GraphQLKtorClient>()

  fun forShop(shop: ShopDomain, apiVersion: String): GraphQLKtorClient =
    cache.getOrPut("${shop.host}/$apiVersion") {
      GraphQLKtorClient(URI(shop.adminGraphqlUrl(apiVersion)).toURL(), httpClient)
    }
}
