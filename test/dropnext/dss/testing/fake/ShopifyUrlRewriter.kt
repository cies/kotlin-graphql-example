package dropnext.dss.testing.fake

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor

/**
 * Builds an [HttpClient] whose outbound requests to any `.myshopify.com` host are silently
 * rewritten to `http://localhost:<fakePort>/...`. Lets production code keep its real Shopify URLs
 * while tests serve responses from [FakeShopifyGraphqlServer].
 */
fun shopifyRewritingHttpClient(fakePort: Int): HttpClient =
  HttpClient(OkHttp) {
    engine {
      addInterceptor(rewriteShopifyHostInterceptor(fakePort))
      config {
        connectTimeout(2, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
        writeTimeout(5, TimeUnit.SECONDS)
      }
    }
    install(HttpTimeout) {
      requestTimeoutMillis = 5_000
      connectTimeoutMillis = 2_000
      socketTimeoutMillis = 5_000
    }
    install(ContentNegotiation) { json() }
  }

private fun rewriteShopifyHostInterceptor(fakePort: Int): Interceptor =
  Interceptor { chain ->
    val original = chain.request()
    val url = original.url
    if (url.host.endsWith(".myshopify.com") || url.host == "myshopify.com") {
      val rewritten = url.newBuilder()
        .scheme("http")
        .host("localhost")
        .port(fakePort)
        .build()
      chain.proceed(original.newBuilder().url(rewritten).build())
    } else {
      chain.proceed(original)
    }
  }
