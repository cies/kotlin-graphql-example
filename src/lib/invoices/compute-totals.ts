/**
 * Shared invoice line totals (matches createInvoice / updateInvoice).
 */
export function computeTotals(
  lines: Array<{ quantity: string; unitPrice: string }>,
  vatRate: number,
  vatIncluded: boolean = false,
  discountType: string = "NONE",
  discountValue: number = 0,
  discountBeforeTax: boolean = true
) {
  const lineSum = lines.reduce((sum, l) => {
    return sum + parseFloat(l.quantity) * parseFloat(l.unitPrice);
  }, 0);

  let discountAmount = 0;

  if (discountBeforeTax) {
    if (discountType === "PERCENTAGE") discountAmount = lineSum * (discountValue / 100);
    else if (discountType === "FIXED") discountAmount = Math.min(discountValue, lineSum);

    const taxable = lineSum - discountAmount;
    if (vatIncluded && vatRate > 0) {
      const vat = (taxable * vatRate) / (1 + vatRate);
      return {
        subtotal: lineSum.toFixed(2),
        discount: discountAmount.toFixed(2),
        vat: vat.toFixed(2),
        total: taxable.toFixed(2),
      };
    }
    const vat = taxable * vatRate;
    const total = taxable + vat;
    return {
      subtotal: lineSum.toFixed(2),
      discount: discountAmount.toFixed(2),
      vat: vat.toFixed(2),
      total: total.toFixed(2),
    };
  } else {
    if (vatIncluded && vatRate > 0) {
      const vat = (lineSum * vatRate) / (1 + vatRate);
      const grossTotal = lineSum;
      if (discountType === "PERCENTAGE") discountAmount = grossTotal * (discountValue / 100);
      else if (discountType === "FIXED") discountAmount = Math.min(discountValue, grossTotal);
      return {
        subtotal: lineSum.toFixed(2),
        discount: discountAmount.toFixed(2),
        vat: vat.toFixed(2),
        total: (grossTotal - discountAmount).toFixed(2),
      };
    }
    const vat = lineSum * vatRate;
    const grossTotal = lineSum + vat;
    if (discountType === "PERCENTAGE") discountAmount = grossTotal * (discountValue / 100);
    else if (discountType === "FIXED") discountAmount = Math.min(discountValue, grossTotal);
    return {
      subtotal: lineSum.toFixed(2),
      discount: discountAmount.toFixed(2),
      vat: vat.toFixed(2),
      total: (grossTotal - discountAmount).toFixed(2),
    };
  }
}
