package com.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

fun createSharedHttpClient(): HttpClient =
  HttpClient(OkHttp) {
    engine {
      config {
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
      }
    }
    install(ContentNegotiation) {
      json(
        Json {
          ignoreUnknownKeys = true
          isLenient = true
        },
      )
    }
    install(Logging) {
      logger = Logger.DEFAULT
      level = LogLevel.INFO
    }
  }
