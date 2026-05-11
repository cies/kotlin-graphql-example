package dropnext.dss

import dropnext.dss.shopify.adminGraphqlJsonUrl
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.HttpClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [GraphQLKtorClient] instances keyed by shop + API version so that a new client object
 * is not allocated on every request. The underlying [httpClient] is shared.
 */
class GraphQLClientCache(private val httpClient: HttpClient) {
  private val cache = ConcurrentHashMap<String, GraphQLKtorClient>()

  fun forShop(shop: String, apiVersion: String): GraphQLKtorClient {
    val key = "$shop/$apiVersion"
    return cache.getOrPut(key) {
      val url = URI(adminGraphqlJsonUrl(shop, apiVersion)).toURL()
      GraphQLKtorClient(url, httpClient)
    }
  }
}
