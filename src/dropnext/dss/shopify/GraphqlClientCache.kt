package dropnext.dss.shopify

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap


/**
 * Caches [com.expediagroup.graphql.client.ktor.GraphQLKtorClient] instances keyed by shop + API version so that a new client object
 * is not allocated on every request. The underlying [httpClient] is shared (one OkHttp connection
 * pool), so the only thing actually cached is the per-shop URL binding plus a small wrapper.
 *
 * Callers get a client with `forShop(shop, apiVersion)` and pass the Admin token per-call via the
 * `X-Shopify-Access-Token` header — tokens are never baked into a cached client.
 */
class GraphqlClientCache(private val httpClient: HttpClient) {
  private val cache = ConcurrentHashMap<String, GraphQLKtorClient>()

  fun forShop(shop: String, apiVersion: String): GraphQLKtorClient {
    return cache.getOrPut("$shop/$apiVersion") {
      GraphQLKtorClient(URI(adminGraphqlJsonUrl(shop, apiVersion)).toURL(), httpClient)
    }
  }
}
