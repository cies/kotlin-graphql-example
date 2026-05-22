package dropnext.dss

import dropnext.dss.lib.dss.dto.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.dss.dto.TrackingUpdateRequest
import dropnext.dss.lib.json.AppJson
import kotlin.test.Test

/**
 * Guards that **openapi.json** example-shaped bodies decode into the Gradle-generated Kotlin DTOs
 * (`openApiGenerate` against repo-root **openapi.json**).
 */
class OpenapiWebhookBodySerializationTest {

  private val json = AppJson

  @Test
  fun `tracking webhook body decodes TrackingUpdateRequest with optional message absent`() {
    val parsed =
      json.decodeFromString<TrackingUpdateRequest>(
        """
        {
          "shopify_subdomain": "acme-downtown",
          "shopify_order_id": 978001,
          "tracking_number": "1Z999AA10123456784",
          "status": "in_transit",
          "happened_at": "2026-04-02T08:30:00Z"
        }
        """.trimIndent(),
      )
    assert(parsed.shopifySubdomain == "acme-downtown")
    assert(parsed.shopifyOrderId == 978001L)
    assert(parsed.status == "in_transit")
    assert(parsed.message == null)
  }

  @Test
  fun `sync webhook body decodes SyncShipmentsWithFulfillmentsRequest`() {
    val parsed =
      json.decodeFromString<SyncShipmentsWithFulfillmentsRequest>(
        """
        {
          "shopify_subdomain": "acme-downtown",
          "shopify_order_id": 1001,
          "shipments": [{
            "tracking_number": "1Z999AA10123456784",
            "carrier": "UPS",
            "tracking_url": "https://www.ups.com/track?tracknum=1Z999AA10123456784",
            "line_items":[{"product_variant_id":101,"quantity":1}]
          }]
        }
        """.trimIndent(),
      )
    assert(parsed.shopifySubdomain == "acme-downtown")
    assert(parsed.shopifyOrderId == 1001L)
    val s = parsed.shipments.single()
    assert(s.trackingNumber == "1Z999AA10123456784")
    assert(s.carrier == "UPS")
    val line = s.lineItems.single()
    assert(line.productVariantId == 101L)
    assert(line.quantity == 1)
  }
}
