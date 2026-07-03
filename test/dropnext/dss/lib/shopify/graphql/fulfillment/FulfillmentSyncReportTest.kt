package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment
import kotlin.test.Test

class FulfillmentSyncReportTest {

  @Test
  fun `parseCreatedFulfillmentId uses legacyResourceId when present`() {
    val fulfillment =
      CreatedFulfillment(
        id = "gid://shopify/Fulfillment/5001",
        legacyResourceId = "5001",
      )
    assert(parseCreatedFulfillmentId(fulfillment) == 5001L)
  }

  @Test
  fun `parseCreatedFulfillmentId falls back to gid when legacyResourceId is blank`() {
    val fulfillment =
      CreatedFulfillment(
        id = "gid://shopify/Fulfillment/7777",
        legacyResourceId = "",
      )
    assert(parseCreatedFulfillmentId(fulfillment) == 7777L)
  }

  @Test
  fun `parseCreatedFulfillmentId returns null when id is unparseable`() {
    val fulfillment =
      CreatedFulfillment(
        id = "not-a-gid",
        legacyResourceId = "also-not-a-number",
      )
    assert(parseCreatedFulfillmentId(fulfillment) == null)
  }

  @Test
  fun `formatSyncShipmentsLogLine includes all stats and fulfillment ids`() {
    val line =
      formatSyncShipmentsLogLine(
        shop = "acme",
        orderId = 1001L,
        stats =
          SyncShipmentsRunStats(
            canceledCount = 2,
            createdCount = 3,
            skippedLines = 1,
            skippedShipments = 0,
          ),
        fulfillmentIds = listOf(5001L, 5002L, 5003L),
      )
    assert("orderId=1001" in line)
    assert("shop=acme" in line)
    assert("canceled=2" in line)
    assert("created=3" in line)
    assert("skippedLines=1" in line)
    assert("skippedShipments=0" in line)
    assert("fulfillmentIds=[5001, 5002, 5003]" in line)
  }
}
