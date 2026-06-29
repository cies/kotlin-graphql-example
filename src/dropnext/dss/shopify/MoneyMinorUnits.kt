package dropnext.dss.shopify

import kotlin.math.roundToLong


// TODO: shopifyDecimalToMinorUnits / minorUnitsToShopifyDecimal assume a 100-based minor unit
//       currency; not all currencies are like that (JPY/KRW are zero-decimal, BHD/JOD/KWD/OMR/TND
//       are 1000-based). Only used for order-total fallback when Shopify's order total is missing;
//       the monolith owns rounding from decimal strings + ISO currency on the wire contract.
/**
 * Shopify money amount for monolith wire format. Non-blank [amount] is returned unchanged
 * (Shopify owns rounding on their side). Blank or null becomes `"0.00"` so required API fields
 * are never sent empty.
 */
fun shopifyMoneyAmountForWire(amount: String?): String =
  amount?.trim()?.takeIf { it.isNotEmpty() } ?: "0.00"

/** Converts a Shopify decimal-string amount (e.g. `"12.34"`) to integer minor units (cents). */
fun shopifyDecimalToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}

/** Formats non-negative integer minor units (cents) as a Shopify-style decimal string (e.g. `"12.34"`). */
fun minorUnitsToShopifyDecimal(minorUnits: Long): String {
  require(minorUnits >= 0L) { "minorUnits must be non-negative, got $minorUnits" }
  return "${minorUnits / 100}.${(minorUnits % 100).toString().padStart(2, '0')}"
}
