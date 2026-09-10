package dropnext.dss.testutil.fixture

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem


/**
 * The monolith's shipment as the sync endpoint receives it. Add a parameter here rather than a
 * fifth private copy in a test class: the four that existed had drifted into four signatures for
 * the same object.
 */
internal fun shipment(
  variantId: Long = 101L,
  quantity: Int = 1,
  tracking: String = "1Z999",
  carrier: String? = "UPS",
  trackingUrl: String? = null,
): Shipment =
  Shipment(
    trackingNumber = tracking,
    carrier = carrier,
    trackingUrl = trackingUrl,
    lineItems = listOf(ShipmentLineItem(productVariantId = variantId, quantity = quantity)),
  )
