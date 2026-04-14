package com.example.shopify

import kotlinx.serialization.Serializable

@Serializable
data class FulfillmentCreateDemoBody(
  val shop: String,
  val fulfillmentOrderId: String,
  val trackingNumber: String,
  val company: String? = null,
  val trackingUrl: String? = null,
  val notifyCustomer: Boolean = false,
)

@Serializable
data class FulfillmentTrackingUpdateDemoBody(
  val shop: String,
  val fulfillmentId: String,
  val trackingNumber: String,
  val company: String? = null,
  val trackingUrl: String? = null,
  val notifyCustomer: Boolean? = null,
)
