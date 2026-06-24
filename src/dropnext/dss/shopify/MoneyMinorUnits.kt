package dropnext.dss.shopify

import kotlin.math.roundToLong


// TODO: This assumes a 100-based minor unit currency; not all currencies are like that.
//       Suggested API change: let monolith own conversion from decimal + currency code.
// (JPY/KRW are zero-decimal, BHD/JOD/KWD/OMR/TND are 1000-based). Carry the ISO currency
// alongside the amount and read the exponent from a table before going multi-currency.
/** Converts a Shopify decimal-string amount (e.g. `"12.34"`) to integer minor units (cents). */
fun shopifyDecimalToMinorUnits(amountDecimal: String): Long {
  val d = amountDecimal.toDoubleOrNull() ?: return 0L
  return (d * 100.0).roundToLong()
}
