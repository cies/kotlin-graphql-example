package dropnext.dss.lib.monolith

import dropnext.dss.lib.dss.dto.CreateShopifyOrderRequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

class HttpMonolithService(
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiKey: String?,
) : MonolithService {

  val createOrderPath = "/orders"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override suspend fun postCreateOrder(request: CreateShopifyOrderRequest): CreateOrderResult {
    val url = baseUrl.trimEnd('/') + createOrderPath
    val response = httpClient.post(url) {
      contentType(ContentType.Application.Json)
      if (!apiKey.isNullOrBlank()) {
        header("Authorization", "Bearer $apiKey")
      }
      setBody(json.encodeToString(CreateShopifyOrderRequest.serializer(), request))
    }
    val body = response.bodyAsText() // TODO: parse the payload using (generated DTOs preferably)
    return if (response.status == HttpStatusCode.OK) {
      CreateOrderResult.HttpResponseSummary(response.status.value, body)
    } else {
      val safeDetail = body.take(512)
      CreateOrderResult.Error(response.status.value, safeDetail)
    }
  }
}
