package dropnext.dss.lib.shopify.graphql

import dropnext.graphql.generated.enums.FulfillmentEventStatus


/**
 * Maps monolith / AfterShip style status strings (e.g. `in_transit`, `In Transit`, `IN-TRANSIT`)
 * to Shopify's [FulfillmentEventStatus].
 *
 * The [Known] / [Unknown] split exists so the handler can return `400 invalid request` for
 * an unsupported status instead of a `500` from a mid-flight enum throw:
 * the parser is the boundary that absorbs upstream string variation.
 */
sealed interface ParsedFulfillmentStatus {
  data class Known(val value: FulfillmentEventStatus) : ParsedFulfillmentStatus

  data class Unknown(val raw: String) : ParsedFulfillmentStatus


  companion object {
    fun parseFulfillmentEventStatus(raw: String): ParsedFulfillmentStatus {
      // Upstream callers send `in-transit`, `in_transit`, `IN TRANSIT` interchangeably; normalise
      // to Shopify's `UPPER_SNAKE_CASE` enum form before lookup so the matcher stays one-liner.
      val normalized = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
      val mapped = FulfillmentEventStatus.entries.find { it.name == normalized }
        ?: when (normalized) {
          "IN_TRANSIT", "TRANSIT" -> FulfillmentEventStatus.IN_TRANSIT
          "DELIVERED", "DELIVERY" -> FulfillmentEventStatus.DELIVERED
          "OUT_FOR_DELIVERY" -> FulfillmentEventStatus.OUT_FOR_DELIVERY
          "CONFIRMED", "PENDING", "INFO_RECEIVED" -> FulfillmentEventStatus.CONFIRMED
          "FAILURE", "FAILED" -> FulfillmentEventStatus.FAILURE
          else -> null
        }
      return if (mapped == null) Unknown(raw)
      else Known(mapped)
    }
  }
}
