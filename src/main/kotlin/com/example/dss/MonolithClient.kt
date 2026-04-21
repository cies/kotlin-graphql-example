package com.example.dss

import com.example.dss.dto.CreateShopifyOrderRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

class MonolithClient(
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiKey: String?,
  private val createOrderPath: String,
) {
  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }

  suspend fun postCreateOrder(request: CreateShopifyOrderRequest): Result<HttpResponseSummary> {
    val url = baseUrl.trimEnd('/') + createOrderPath
    val response =
      httpClient.post(url) {
        contentType(ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) {
          header("Authorization", "Bearer $apiKey")
        }
        setBody(json.encodeToString(CreateShopifyOrderRequest.serializer(), request))
      }
    val body = response.bodyAsText()
    return if (response.status == HttpStatusCode.OK) {
      Result.success(HttpResponseSummary(response.status.value, body))
    } else {
      val safeDetail = body.take(512)
      Result.failure(RuntimeException("Monolith HTTP ${response.status}: $safeDetail"))
    }
  }
}

data class HttpResponseSummary(
  val status: Int,
  val body: String,
)
