package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.graphql.generated.enums.FulfillmentEventStatus

/** Maps monolith / AfterShip style strings (e.g. `in_transit`) to [FulfillmentEventStatus]. */
// TODO(cies): explain why you made this type...
sealed interface ParsedFulfillmentStatus {
  data class Known(val value: FulfillmentEventStatus) : ParsedFulfillmentStatus

  data class Unknown(val raw: String) : ParsedFulfillmentStatus


  companion object {
    fun parseFulfillmentEventStatus(raw: String): ParsedFulfillmentStatus {
      // TODO(cies): why do we need normalization?
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
