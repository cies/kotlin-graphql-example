package dropnext.dss.shopify

import kotlin.test.Test

class MoneyMinorUnitsTest {

  @Test
  fun `converts plain decimal to minor units`() {
    assert(shopifyDecimalToMinorUnits("12.34") == 1234L)
    assert(shopifyDecimalToMinorUnits("0.01") == 1L)
    assert(shopifyDecimalToMinorUnits("100") == 10_000L)
  }

  @Test
  fun `rounds half-cent up`() {
    assert(shopifyDecimalToMinorUnits("19.995") == 2000L)
    assert(shopifyDecimalToMinorUnits("19.999") == 2000L)
  }

  @Test
  fun `rounds sub-half-cent down`() {
    assert(shopifyDecimalToMinorUnits("19.994") == 1999L)
    assert(shopifyDecimalToMinorUnits("19.991") == 1999L)
  }

  @Test
  fun `zero amount maps to zero minor units`() {
    assert(shopifyDecimalToMinorUnits("0") == 0L)
    assert(shopifyDecimalToMinorUnits("0.00") == 0L)
  }

  @Test
  fun `negative amount preserves sign`() {
    assert(shopifyDecimalToMinorUnits("-5.00") == -500L)
    assert(shopifyDecimalToMinorUnits("-0.01") == -1L)
  }

  @Test
  fun `unparseable amount returns zero`() {
    assert(shopifyDecimalToMinorUnits("n/a") == 0L)
    assert(shopifyDecimalToMinorUnits("") == 0L)
    assert(shopifyDecimalToMinorUnits("USD 12.34") == 0L)
  }

  @Test
  fun `shopifyMoneyAmountForWire passes through non-blank amounts`() {
    assert(shopifyMoneyAmountForWire("19.99") == "19.99")
    assert(shopifyMoneyAmountForWire("  10.00  ") == "10.00")
    assert(shopifyMoneyAmountForWire("n/a") == "n/a")
  }

  @Test
  fun `shopifyMoneyAmountForWire defaults blank to zero decimal`() {
    assert(shopifyMoneyAmountForWire(null) == "0.00")
    assert(shopifyMoneyAmountForWire("") == "0.00")
    assert(shopifyMoneyAmountForWire("   ") == "0.00")
  }

  @Test
  fun `minorUnitsToShopifyDecimal formats cents`() {
    assert(minorUnitsToShopifyDecimal(1234L) == "12.34")
    assert(minorUnitsToShopifyDecimal(1L) == "0.01")
    assert(minorUnitsToShopifyDecimal(10_000L) == "100.00")
    assert(minorUnitsToShopifyDecimal(0L) == "0.00")
  }
}
