package com.example.dss

import com.example.config.ShopifyConfig
import java.nio.file.Path
import java.nio.file.Paths

data class DssAppConfig(
  val shopify: ShopifyConfig,
  /** Base URL for monolith (e.g. `https://monolith.internal`). If null, order webhook does not POST. */
  val monolithBaseUrl: String?,
  /** Sent as `Authorization: Bearer …` when non-blank. */
  val monolithApiKey: String?,
  /** Path appended to monolith base for create order (default `/orders`). */
  val monolithCreateOrderPath: String,
  /** When set, DSS REST routes require header `X-DSS-Internal-Secret` (except health/install/oauth/webhooks). */
  val dssInternalSecret: String?,
  /** Directory for `stores.json` persistence. */
  val dataDir: Path,
  val enableDemoRoutes: Boolean,
  /** When false (default), `MONOLITH_BASE_URL` must be `https://` (except unset). */
  val allowInsecureMonolithUrl: Boolean,
) {
  companion object {
    fun fromEnv(): DssAppConfig? {
      val shopify = ShopifyConfig.fromEnv() ?: return null
      val base = System.getenv("MONOLITH_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
      val key = System.getenv("MONOLITH_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
      val rawPath =
        System.getenv("MONOLITH_CREATE_ORDER_PATH")?.trim()?.takeIf { it.isNotEmpty() } ?: "/orders"
      val path =
        when {
          rawPath.startsWith('/') -> rawPath
          else -> "/$rawPath"
        }
      val secret = System.getenv("DSS_INTERNAL_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
      val data =
        System.getenv("DSS_DATA_DIR")?.trim()?.takeIf { it.isNotEmpty() }
          ?: "./data"
      val demos =
        System.getenv("ENABLE_DEMO_ROUTES")?.trim()?.equals("true", ignoreCase = true) == true
      val allowInsecure =
        System.getenv("DSS_ALLOW_INSECURE_MONOLITH")?.trim()?.equals("true", ignoreCase = true) == true
      if (base != null && base.startsWith("http:", ignoreCase = true) && !allowInsecure) {
        error(
          "MONOLITH_BASE_URL must use https. For local http only, set DSS_ALLOW_INSECURE_MONOLITH=true",
        )
      }
      return DssAppConfig(
        shopify = shopify,
        monolithBaseUrl = base,
        monolithApiKey = key,
        monolithCreateOrderPath = path,
        dssInternalSecret = secret,
        dataDir = Paths.get(data),
        enableDemoRoutes = demos,
        allowInsecureMonolithUrl = allowInsecure,
      )
    }
  }
}
