package dropnext.dss.lib.monolith

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.lib.shopify.ShopDomain
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import io.ktor.client.HttpClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap


/**
 * Production [ShopifyGraphqlServiceFactory]: resolves the Admin token via in-memory cache → monolith
 * fallback, caches a per-shop [GraphQLKtorClient] (cheap reuse on each call), and hands back an
 * [HttpShopifyGraphqlService] bound to that token. Tokens themselves are never logged — only the
 * resolved shop subdomain.
 */
class HttpShopifyGraphqlServiceFactory(
  httpClient: HttpClient,
  private val tokens: ShopAccessTokenCache,
  private val monolith: MonolithService,
  private val apiVersion: String,
) : ShopifyGraphqlServiceFactory {

  private val gqlClientCache = GraphqlClientCache(httpClient)

  override suspend fun forShop(shop: ShopDomain, explicitToken: String?): ShopifyGraphqlService? {
    val token = explicitToken ?: resolveToken(shop) ?: return null
    val gqlClient = gqlClientCache.forShop(shop, apiVersion)
    return HttpShopifyGraphqlService(shop, gqlClient, token)
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
          "subdomain=${shop.subdomainShort}",
        )
        null
      }
    }
  }
}

/**
 * Caches [GraphQLKtorClient] instances keyed by shop + API version so a new client object is not
 * allocated on every request. The underlying [httpClient] is shared (one OkHttp connection
 * pool), so the only thing actually cached is the per-shop URL binding plus a small wrapper.
 *
 * Intentionally does NOT implement `Closeable`: `GraphQLKtorClient.close()` delegates to
 * `httpClient.close()` on the *shared* client, which `DssDependencies.close()` already closes
 * once. Calling `close()` on every cache entry would close the shared client repeatedly.
 *
 * File-private — callers go through [HttpShopifyGraphqlServiceFactory.forShop], which hands back a
 * [ShopifyGraphqlService] already bound to the resolved Admin token.
 */
private class GraphqlClientCache(private val httpClient: HttpClient) {
  private val cache = ConcurrentHashMap<String, GraphQLKtorClient>()

  fun forShop(shop: ShopDomain, apiVersion: String): GraphQLKtorClient =
    cache.getOrPut("${shop.host}/$apiVersion") {
      GraphQLKtorClient(URI(shop.adminGraphqlUrl(apiVersion)).toURL(), httpClient)
    }
}
