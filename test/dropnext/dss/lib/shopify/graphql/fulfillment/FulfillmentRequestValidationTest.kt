package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import dropnext.dss.lib.shopify.graphql.fulfillment.RequestValidation
import dropnext.dss.lib.shopify.graphql.fulfillment.validateSyncShipmentsRequest
import dropnext.dss.lib.shopify.graphql.fulfillment.validateTrackingUpdateRequest
import kotlin.test.Test

class FulfillmentRequestValidationTest {

  @Test
  fun `rejects non-positive shopify_order_id on sync`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 0L,
                shipments = listOf(validShipment()),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects empty shipments list`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = emptyList(),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects blank tracking number on shipment`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment().copy(trackingNumber = "  ")),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects missing carrier on shipment`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment().copy(carrier = null)),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects shipment with empty line_items`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment().copy(lineItems = emptyList())),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `accepts valid tracking update request`() {
    val result =
        validateTrackingUpdateRequest(
            TrackingUpdateRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                trackingNumber = "1Z999",
                status = "in_transit",
                happenedAt = "2026-04-02T08:30:00Z",
                message = null,
            ),
        )
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `rejects non-positive shopify_order_id on tracking update`() {
    val result =
        validateTrackingUpdateRequest(
            TrackingUpdateRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = -1L,
                trackingNumber = "1Z999",
                status = "in_transit",
                happenedAt = "2026-04-02T08:30:00Z",
                message = null,
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects zero quantity line item`() {
    val shipment =
      validShipment().copy(
        lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 0)),
      )
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(shipment),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `accepts valid sync request`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment()),
            ),
        )
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `rejects blank tracking fields on tracking update`() {
    assert(
      validateTrackingUpdateRequest(
          TrackingUpdateRequest(
              shopifySubdomain = "acme",
              shopifyOrderId = 1001L,
              trackingNumber = "",
              status = "in_transit",
              happenedAt = "2026-04-02T08:30:00Z",
              message = null,
          ),
      ) is RequestValidation.Invalid,
    )
    assert(
      validateTrackingUpdateRequest(
          TrackingUpdateRequest(
              shopifySubdomain = "acme",
              shopifyOrderId = 1001L,
              trackingNumber = "1Z999",
              status = "",
              happenedAt = "2026-04-02T08:30:00Z",
              message = null,
          ),
      ) is RequestValidation.Invalid,
    )
  }

  @Test
  fun `accumulates multiple errors instead of short-circuiting on the first`() {
    val result =
        validateSyncShipmentsRequest(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 0L,
                shipments = listOf(
                    validShipment().copy(trackingNumber = "  ", carrier = null),
                    validShipment().copy(
                        lineItems = listOf(
                            ShipmentLineItem(
                                productVariantId = 101L,
                                quantity = 0
                            )
                        )
                    ),
                ),
            ),
        )
    assert(result is RequestValidation.Invalid)
    val messages = (result as RequestValidation.Invalid).messages
    assert(messages.any { "shopify_order_id" in it })
    assert(messages.any { "tracking_number" in it })
    assert(messages.any { "carrier" in it })
    assert(messages.any { "quantity must be positive" in it })
    assert(messages.size >= 4)
  }

  @Test
  fun `accumulates both tracking errors on tracking update`() {
    val result =
        validateTrackingUpdateRequest(
            TrackingUpdateRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 0L,
                trackingNumber = "",
                status = "",
                happenedAt = "2026-04-02T08:30:00Z",
                message = null,
            ),
        )
    assert(result is RequestValidation.Invalid)
    val messages = (result as RequestValidation.Invalid).messages
    assert(messages.size == 3)
  }

  private fun validShipment(): Shipment =
    Shipment(
      trackingNumber = "1Z999AA10123456784",
      carrier = "UPS",
      trackingUrl = "https://www.ups.com/track",
      lineItems = listOf(ShipmentLineItem(productVariantId = 101L, quantity = 1)),
    )
}
