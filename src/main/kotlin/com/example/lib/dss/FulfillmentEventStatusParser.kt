package com.example.lib.dss

import com.example.graphql.generated.enums.FulfillmentEventStatus

/** Maps monolith / AfterShip style strings (e.g. `in_transit`) to [FulfillmentEventStatus]. */
fun parseFulfillmentEventStatus(raw: String): FulfillmentEventStatus {
  val normalized = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
  return FulfillmentEventStatus.entries.find { it.name == normalized }
    ?: when (normalized) {
      "IN_TRANSIT", "TRANSIT" -> FulfillmentEventStatus.IN_TRANSIT
      "DELIVERED", "DELIVERY" -> FulfillmentEventStatus.DELIVERED
      "OUT_FOR_DELIVERY" -> FulfillmentEventStatus.OUT_FOR_DELIVERY
      "CONFIRMED", "PENDING", "INFO_RECEIVED" -> FulfillmentEventStatus.CONFIRMED
      "FAILURE", "FAILED" -> FulfillmentEventStatus.FAILURE
      else -> FulfillmentEventStatus.__UNKNOWN_VALUE
    }
}
