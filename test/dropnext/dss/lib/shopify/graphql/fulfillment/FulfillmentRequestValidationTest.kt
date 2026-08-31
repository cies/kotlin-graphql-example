package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.monolith.dto.generated.Shipment
import dropnext.dss.lib.monolith.dto.generated.ShipmentLineItem
import dropnext.dss.lib.monolith.dto.generated.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.lib.monolith.dto.generated.TrackingUpdateRequest
import kotlin.test.Test

class FulfillmentRequestValidationTest {

  private fun validate(request: SyncShipmentsWithFulfillmentsRequest): RequestValidation =
    request.validate()

  @Test
  fun `rejects non-positive shopify_order_id on sync`() {
    val result =
        validate(
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
        validate(
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
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment().copy(trackingNumber = "  ")),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `accepts shipment without carrier`() {
    val result =
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(validShipment().copy(carrier = null)),
            ),
        )
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `rejects shipment with empty line_items`() {
    val result =
        validate(
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
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(shipment),
            ),
        )
    assert(result is RequestValidation.Invalid)
  }

  @Test
  fun `rejects duplicate tracking numbers`() {
    val result =
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(
                    validShipment().copy(trackingNumber = "1Z999"),
                    validShipment().copy(trackingNumber = "1Z999"),
                ),
            ),
        )
    assert(result is RequestValidation.Invalid)
    assert("duplicate tracking_number" in (result as RequestValidation.Invalid).message)
  }

  @Test
  fun `accepts distinct tracking numbers across shipments`() {
    val result =
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 1001L,
                shipments = listOf(
                    validShipment().copy(trackingNumber = "1Z999"),
                    validShipment().copy(trackingNumber = "1Z888"),
                ),
            ),
        )
    assert(result is RequestValidation.Valid)
  }

  @Test
  fun `accepts valid sync request`() {
    val result =
        validate(
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
        validate(
            SyncShipmentsWithFulfillmentsRequest(
                shopifySubdomain = "acme",
                shopifyOrderId = 0L,
                shipments = listOf(
                    validShipment().copy(trackingNumber = "  "),
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
    assert(messages.any { "quantity must be positive" in it })
    assert(messages.size >= 3)
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
