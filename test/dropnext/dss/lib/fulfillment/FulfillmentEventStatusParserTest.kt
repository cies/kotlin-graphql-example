package dropnext.dss.lib.fulfillment

import dropnext.graphql.generated.enums.FulfillmentEventStatus
import kotlin.test.Test

class FulfillmentEventStatusParserTest {

  @Test
  fun `maps in_transit synonyms`() {
    assert((parseFulfillmentEventStatus("in_transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((parseFulfillmentEventStatus("IN-TRANSIT") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((parseFulfillmentEventStatus("in transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((parseFulfillmentEventStatus("transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
  }

  @Test
  fun `maps delivery synonyms`() {
    assert((parseFulfillmentEventStatus("delivered") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.DELIVERED)
    assert((parseFulfillmentEventStatus("delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.DELIVERED)
  }

  @Test
  fun `maps out for delivery`() {
    assert((parseFulfillmentEventStatus("out_for_delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.OUT_FOR_DELIVERY)
    assert((parseFulfillmentEventStatus("out for delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.OUT_FOR_DELIVERY)
  }

  @Test
  fun `maps confirmation synonyms`() {
    assert((parseFulfillmentEventStatus("confirmed") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
    assert((parseFulfillmentEventStatus("pending") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
    assert((parseFulfillmentEventStatus("info_received") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
  }

  @Test
  fun `maps failure synonyms`() {
    assert((parseFulfillmentEventStatus("failure") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.FAILURE)
    assert((parseFulfillmentEventStatus("failed") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.FAILURE)
  }

  @Test
  fun `unknown status preserves the raw input`() {
    val result = parseFulfillmentEventStatus("yeeted")
    assert(result is ParsedFulfillmentStatus.Unknown)
    assert((result as ParsedFulfillmentStatus.Unknown).raw == "yeeted")
  }

  @Test
  fun `accepts canonical enum names directly`() {
    val result = parseFulfillmentEventStatus("READY_FOR_PICKUP")
    assert(result is ParsedFulfillmentStatus.Known)
    assert((result as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.READY_FOR_PICKUP)
  }
}
