import type { Currency, PaymentMethod } from "@prisma/client";
import type { InvoicePdfData } from "@/lib/pdf/invoice-pdf";
import {
  escapeHtml,
  formatReceiptLongDate,
  formatReceiptPeriodRange,
  paymentReferenceDisplay,
  qtyLabelForReceiptLine,
  RECEIPT_METHOD_LABELS,
  splitAddressBlock,
} from "@/lib/receipt/receipt-shared";
import { formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { resolveCorporateChrome } from "@/lib/pdf/invoice-templates/corporate-tones";
import {
  formatDiscountLabel,
  formatVatLabel,
  getInvoiceDisplayStatus,
} from "@/lib/pdf/invoice-templates/types";

export type InvoiceDetailsPaymentSlice = {
  paidAt: Date;
  method: PaymentMethod;
  amount: string;
  currency: Currency;
  stripeChargeId?: string | null;
  notes?: string | null;
};

/**
 * Formal invoice summary HTML (inline styles) - used in invoice emails and staff preview.
 * Styled to match payment receipt emails (`receiptDetailsHtml`) / Corporate chrome.
 */
export function invoiceDetailsHtml(
  data: InvoicePdfData,
  payments: InvoiceDetailsPaymentSlice[],
  opts?: { discountValue?: string | null }
): string {
  const style = normalizeNumberFormatStyle(data.numberFormatStyle);
  const chrome = resolveCorporateChrome(data.accentColor);
  const inv = data.invoice;
  const cur = inv.currency;
  const fmt = (amount: string | number, currency: Currency = cur) =>
    formatCurrency(amount, currency, style);

  const invPeriod = formatReceiptPeriodRange(inv.periodFrom ?? null, inv.periodTo ?? null);
  const issueDate = formatReceiptLongDate(new Date(inv.issuedAt));
  const dueDate = inv.dueDate ? formatReceiptLongDate(new Date(inv.dueDate)) : "-";
  const statusLabel = getInvoiceDisplayStatus(inv.status, inv.displayStatus);

  const totalNum = parseFloat(inv.total);
  const paidNum = parseFloat(inv.amountPaid);
  const dueNum = Math.max(0, totalNum - paidNum);

  const orgLines = splitAddressBlock(data.org.address ?? "");
  const phoneLines = splitAddressBlock(data.org.phoneContact ?? "");

  const orgBlock =
    `<div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:6px;text-transform:uppercase;letter-spacing:0.05em">From</div>` +
    `<div style="font-size:13px;font-weight:700;color:#111827;margin-bottom:4px">${escapeHtml(data.org.name)}</div>` +
    orgLines
      .map((l) => `<div style="font-size:12px;color:#4b5563;line-height:1.45">${escapeHtml(l)}</div>`)
      .join("") +
    (data.org.vat?.trim()
      ? `<div style="font-size:12px;color:#4b5563;margin-top:4px">VAT: ${escapeHtml(data.org.vat.trim())}</div>`
      : "") +
    (data.org.billingEmail?.trim()
      ? `<div style="font-size:12px;color:#4b5563;margin-top:4px">${escapeHtml(data.org.billingEmail.trim())}</div>`
      : "") +
    phoneLines
      .map((l) => `<div style="font-size:12px;color:#4b5563;line-height:1.45;margin-top:2px">${escapeHtml(l)}</div>`)
      .join("");

  const customerAddrLines = splitAddressBlock(data.customer.address ?? "");

  const billBlock =
    `<div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:6px;text-transform:uppercase;letter-spacing:0.05em">Bill to</div>` +
    `<div style="font-size:13px;font-weight:700;color:#111827;margin-bottom:4px">${escapeHtml(data.customer.name)}</div>` +
    (data.customer.email
      ? `<div style="font-size:12px;color:#4b5563;margin-top:2px">${escapeHtml(data.customer.email)}</div>`
      : "") +
    customerAddrLines
      .map((l) => `<div style="font-size:12px;color:#4b5563;line-height:1.45">${escapeHtml(l)}</div>`)
      .join("") +
    (data.customer.phone?.trim()
      ? `<div style="font-size:12px;color:#4b5563;margin-top:6px">${escapeHtml(data.customer.phone.trim())}</div>`
      : "");

  const lineRows = data.lines
    .map((line, idx) => {
      const qty = qtyLabelForReceiptLine(line.quantity, line.qtyType ?? "QTY");
      const subParts: string[] = [];
      if (line.description?.trim()) subParts.push(line.description.trim());
      if (idx === 0 && invPeriod) subParts.push(invPeriod);
      const periodOrDesc = subParts.length ? subParts.join("\n") : null;
      const sub = periodOrDesc
        ? `<span style="display:block;font-size:11px;color:#475569;line-height:1.35;margin-top:2px;white-space:pre-line">${escapeHtml(periodOrDesc)}</span>`
        : "";
      const altBg = idx % 2 === 1 ? `background-color:${chrome.tableRowAltBg};` : "";
      return `<tr style="border-bottom:1px solid ${chrome.tableRowBorder};${altBg}">
<td style="padding:10px 8px 10px 0;vertical-align:top;font-size:12px;color:#374151">
<strong>${escapeHtml(line.name)}</strong>${sub}
</td>
<td style="padding:10px 8px;text-align:right;vertical-align:top;font-size:12px;color:#4b5563">${escapeHtml(qty)}</td>
<td style="padding:10px 8px;text-align:right;vertical-align:top;font-size:12px;color:#4b5563">${escapeHtml(fmt(line.unitPrice, cur))}</td>
<td style="padding:10px 0 10px 8px;text-align:right;vertical-align:top;font-size:12px;font-weight:600;color:#111827">${escapeHtml(fmt(line.total, cur))}</td>
</tr>`;
    })
    .join("");

  const discountNum = parseFloat(inv.discount ?? "0");
  const showDiscount = inv.discountType && inv.discountType !== "NONE" && discountNum > 0;
  const discountLabel = formatDiscountLabel(
    inv.discountType ?? undefined,
    opts?.discountValue ?? undefined,
    inv.discountBeforeTax
  );

  const vatLabel = formatVatLabel(inv.vatRate, inv.vatIncluded);

  const totalsInnerRows: string[] = [];
  totalsInnerRows.push(
    `<tr><td style="padding:6px 12px;font-size:12px;color:#6b7280;border-bottom:1px solid ${chrome.totalRowBorder}">Subtotal</td>` +
      `<td style="padding:6px 12px;text-align:right;font-size:12px;color:#1a1a1a;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(fmt(inv.subtotal, cur))}</td></tr>`
  );
  if (showDiscount) {
    totalsInnerRows.push(
      `<tr><td style="padding:6px 12px;font-size:12px;color:#6b7280;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(discountLabel)}</td>` +
        `<td style="padding:6px 12px;text-align:right;font-size:12px;color:#1a1a1a;border-bottom:1px solid ${chrome.totalRowBorder}">−${escapeHtml(fmt(inv.discount ?? "0", cur))}</td></tr>`
    );
  }
  totalsInnerRows.push(
    `<tr><td style="padding:6px 12px;font-size:12px;color:#6b7280;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(vatLabel)}</td>` +
      `<td style="padding:6px 12px;text-align:right;font-size:12px;color:#1a1a1a;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(fmt(inv.vat, cur))}</td></tr>`
  );
  totalsInnerRows.push(
    `<tr><td style="padding:6px 12px;font-size:12px;font-weight:600;color:#111827;border-bottom:1px solid ${chrome.totalRowBorder}">Total</td>` +
      `<td style="padding:6px 12px;text-align:right;font-size:12px;font-weight:700;color:#111827;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(fmt(inv.total, cur))}</td></tr>`
  );
  totalsInnerRows.push(
    `<tr><td style="padding:6px 12px;font-size:12px;color:#15803d">Amount paid</td>` +
      `<td style="padding:6px 12px;text-align:right;font-size:12px;font-weight:600;color:#15803d">${escapeHtml(fmt(inv.amountPaid, cur))}</td></tr>`
  );
  totalsInnerRows.push(
    `<tr><td style="padding:6px 12px;font-size:12px;color:#c2410c">Amount due</td>` +
      `<td style="padding:6px 12px;text-align:right;font-size:12px;font-weight:600;color:#c2410c">${escapeHtml(fmt(dueNum.toFixed(2), cur))}</td></tr>`
  );

  const summaryExtra: string[] = [];
  if (invPeriod) {
    summaryExtra.push(
      `<div><strong style="color:#111827">Billing period:</strong> ${escapeHtml(invPeriod)}</div>`
    );
  }
  summaryExtra.push(
    `<div><strong style="color:#111827">Status:</strong> ${escapeHtml(statusLabel)}</div>`
  );

  const paymentRows =
    payments.length === 0
      ? ""
      : payments
          .map((p, idx) => {
            const methodLabel = RECEIPT_METHOD_LABELS[p.method];
            const paidDate = formatReceiptLongDate(new Date(p.paidAt));
            const payRef = paymentReferenceDisplay(p.stripeChargeId, p.notes);
            const altBg = idx % 2 === 1 ? `background-color:${chrome.tableRowAltBg};` : "";
            return `<tr style="border-bottom:1px solid ${chrome.tableRowBorder};${altBg}">
<td style="padding:10px;font-size:12px;color:#4b5563">${escapeHtml(paidDate)}</td>
<td style="padding:10px;font-size:12px;color:#111827">${escapeHtml(methodLabel)}</td>
<td style="padding:10px;text-align:right;font-size:11px;font-family:ui-monospace,monospace;color:#4b5563">${escapeHtml(payRef)}</td>
<td style="padding:10px;text-align:right;font-size:12px;font-weight:600">${escapeHtml(fmt(p.amount, p.currency))}</td>
</tr>`;
          })
          .join("");

  const paymentsSection =
    payments.length === 0
      ? ""
      : `<tr>
    <td style="padding-top:24px">
      <div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:8px;text-transform:uppercase;letter-spacing:0.05em">Payments</div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse">
        <thead>
          <tr style="background:${chrome.tableHeaderBg}">
            <th style="text-align:left;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Date</th>
            <th style="text-align:left;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Method</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Reference</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Amount</th>
          </tr>
        </thead>
        <tbody>${paymentRows}</tbody>
      </table>
    </td>
  </tr>`;

  return `
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-family:Helvetica,Arial,sans-serif;color:#111827">
  <tr>
    <td style="vertical-align:top;padding-bottom:4px">
      <div style="font-size:24px;font-weight:700;color:${chrome.headerCellText};letter-spacing:-0.02em">Invoice</div>
      <div style="font-size:11px;color:#6b7280;margin-top:4px">${escapeHtml(data.org.name)} · ${escapeHtml(inv.number)}</div>
    </td>
  </tr>
  <tr>
    <td style="padding-top:14px;padding-bottom:18px">
      <div style="border:1px solid ${chrome.datesBorder};background:${chrome.datesBg};border-radius:3px;padding:12px 14px;font-size:12px;line-height:1.55;color:#4b5563">
        <div><strong style="color:#111827">Invoice:</strong> ${escapeHtml(inv.number)}</div>
        <div><strong style="color:#111827">Issue date:</strong> ${escapeHtml(issueDate)}</div>
        <div><strong style="color:#111827">Due date:</strong> ${escapeHtml(dueDate)}</div>
        ${summaryExtra.join("")}
      </div>
    </td>
  </tr>
  <tr>
    <td style="padding-bottom:18px">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
        <td style="width:50%;vertical-align:top;padding-right:16px;border-right:1px solid #e5e7eb">${orgBlock}</td>
        <td style="width:50%;vertical-align:top;padding-left:24px">${billBlock}</td>
      </tr></table>
    </td>
  </tr>
  <tr>
    <td style="padding-bottom:18px">
      <div style="background:${chrome.tableHeaderBg};border-left:4px solid ${chrome.grandBg};border-radius:3px;padding:14px 16px">
        <div style="font-size:10px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.06em;margin-bottom:6px">Summary</div>
        <div style="font-size:16px;font-weight:700;color:#111827">${escapeHtml(fmt(inv.total, cur))} · ${escapeHtml(statusLabel)}</div>
        ${inv.dueDate ? `<div style="font-size:12px;color:#6b7280;margin-top:6px">Due ${escapeHtml(dueDate)}</div>` : ""}
      </div>
    </td>
  </tr>
  <tr>
    <td>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse">
        <thead>
          <tr style="background:${chrome.tableHeaderBg}">
            <th style="text-align:left;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Item / Service</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Qty</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Unit price</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Amount</th>
          </tr>
        </thead>
        <tbody>${lineRows}</tbody>
      </table>
    </td>
  </tr>
  <tr>
    <td style="padding-top:12px">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
        <tr>
          <td align="right">
            <table role="presentation" width="280" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-spacing:0">
              <tr>
                <td style="padding:0;margin:0">
                  <table role="presentation" width="280" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-top:1px solid ${chrome.totalsBorder};border-left:1px solid ${chrome.totalsBorder};border-right:1px solid ${chrome.totalsBorder};border-radius:3px 3px 0 0;overflow:hidden">
                    ${totalsInnerRows.join("")}
                  </table>
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </table>
    </td>
  </tr>
  ${paymentsSection}
  <tr>
    <td style="padding-top:28px;border-top:1px solid #e5e7eb">
      <div style="text-align:right;font-size:11px;color:#9ca3af">Issued by ${escapeHtml(data.org.name)} · Thank you for your business</div>
    </td>
  </tr>
</table>`.trim();
}
