package dropnext.dss.mapper

import java.math.RoundingMode
import java.util.Currency


/**
 * Shopify money amount for monolith wire format. Non-blank [amount] is returned unchanged
 * (Shopify owns rounding on their side). Blank or null becomes `"0.00"` so required API fields
 * are never sent empty.
 */
fun shopifyMoneyAmountForWire(amount: String?): String =
  amount?.trim()?.takeIf { it.isNotEmpty() } ?: "0.00"

/**
 * Converts a Shopify decimal-string amount to integer minor units using the ISO 4217 exponent
 * for [currencyCode]. Unknown or non-ISO codes return 0 (do not guess 100-based cents).
 */
fun shopifyAmountToMinorUnits(amount: String, currencyCode: String): Long {
  val fractionDigits = currencyFractionDigits(currencyCode) ?: return 0L
  val decimal = amount.toBigDecimalOrNull() ?: return 0L
  return decimal
    .movePointRight(fractionDigits)
    .setScale(0, RoundingMode.HALF_UP)
    .toLong()
}

/**
 * Formats non-negative integer minor units as a Shopify-style decimal string using the ISO 4217
 * exponent for [currencyCode].
 */
fun minorUnitsToShopifyAmount(minorUnits: Long, currencyCode: String): String {
  require(minorUnits >= 0L) { "minorUnits must be non-negative, got $minorUnits" }
  val fractionDigits = currencyFractionDigits(currencyCode)
    ?: error("unknown currency code: $currencyCode")
  if (fractionDigits == 0) return minorUnits.toString()
  val padded = minorUnits.toString().padStart(fractionDigits + 1, '0')
  val splitAt = padded.length - fractionDigits
  return "${padded.substring(0, splitAt)}.${padded.substring(splitAt)}"
}

/** ISO 4217 default fraction digits, or null when the code is unknown. */
internal fun currencyFractionDigits(currencyCode: String): Int? {
  val trimmed = currencyCode.trim()
  if (trimmed.isEmpty() || trimmed == "__UNKNOWN_VALUE") return null
  val digits = runCatching { Currency.getInstance(trimmed).defaultFractionDigits }.getOrNull()
    ?: return null
  return digits.takeIf { it >= 0 }
}
