import type { ReceiptPdfData } from "@/lib/pdf/receipt-pdf";
import {
  escapeHtml,
  formatReceiptLongDate,
  formatReceiptPeriodRange,
  paymentReferenceDisplay,
  qtyLabelForReceiptLine,
  RECEIPT_METHOD_LABELS,
  resolveReceiptDisplayNumber,
  splitAddressBlock,
} from "@/lib/receipt/receipt-shared";
import { formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { resolveCorporateChrome } from "@/lib/pdf/invoice-templates/corporate-tones";

/**
 * Formal receipt body HTML (inline styles) - used in receipt emails and on the receipt preview page.
 * Styled to match Corporate invoice PDF chrome (accent tints).
 */
export function receiptDetailsHtml(data: ReceiptPdfData): string {
  const style = normalizeNumberFormatStyle(data.numberFormatStyle);
  const chrome = resolveCorporateChrome(data.accentColor);
  const rcptNum = resolveReceiptDisplayNumber(
    data.payment.receiptNumber,
    data.invoice.number,
    data.paymentId ?? undefined,
    data.payment.paidAt
  );
  const paidDate = formatReceiptLongDate(data.payment.paidAt);
  const invPeriod = formatReceiptPeriodRange(data.invoice.periodFrom, data.invoice.periodTo);
  const methodLabel = RECEIPT_METHOD_LABELS[data.payment.method];
  const payRef = paymentReferenceDisplay(data.payment.stripeChargeId, data.payment.notes);

  const orgLines = splitAddressBlock(data.org.addressBlock);
  const customerAddrLines = splitAddressBlock(data.customer.billingAddressBlock);

  const fmt = (amount: string | number, cur: typeof data.invoice.currency) =>
    formatCurrency(amount, cur, style);

  const lines =
    data.lines.length > 0
      ? data.lines
      : [
          {
            name: `Invoice ${data.invoice.number}`,
            description: null as string | null,
            quantity: "1",
            qtyType: "QTY",
            unitPrice: data.payment.amount,
            total: data.payment.amount,
          },
        ];

  const lineRows = lines
    .map((line, idx) => {
      const qty = qtyLabelForReceiptLine(line.quantity, line.qtyType);
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
<td style="padding:10px 8px;text-align:right;vertical-align:top;font-size:12px;color:#4b5563">${escapeHtml(fmt(line.unitPrice, data.invoice.currency))}</td>
<td style="padding:10px 0 10px 8px;text-align:right;vertical-align:top;font-size:12px;font-weight:600;color:#111827">${escapeHtml(fmt(line.total, data.invoice.currency))}</td>
</tr>`;
    })
    .join("");

  const orgBlock =
    `<div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:6px;text-transform:uppercase;letter-spacing:0.05em">From</div>` +
    `<div style="font-size:13px;font-weight:700;color:#111827;margin-bottom:4px">${escapeHtml(data.org.name)}</div>` +
    orgLines
      .map((l) => `<div style="font-size:12px;color:#4b5563;line-height:1.45">${escapeHtml(l)}</div>`)
      .join("") +
    (data.org.phone
      ? `<div style="font-size:12px;color:#4b5563;margin-top:6px">${escapeHtml(data.org.phone)}</div>`
      : "");

  const billBlock =
    `<div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:6px;text-transform:uppercase;letter-spacing:0.05em">Bill to</div>` +
    `<div style="font-size:13px;font-weight:700;color:#111827;margin-bottom:4px">${escapeHtml(data.customer.name)}</div>` +
    (data.customer.email
      ? `<div style="font-size:12px;color:#4b5563;margin-top:2px">${escapeHtml(data.customer.email)}</div>`
      : "") +
    customerAddrLines
      .map((l) => `<div style="font-size:12px;color:#4b5563;line-height:1.45">${escapeHtml(l)}</div>`)
      .join("");

  return `
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-family:Helvetica,Arial,sans-serif;color:#111827">
  <tr>
    <td style="vertical-align:top;padding-bottom:4px">
      <div style="font-size:24px;font-weight:700;color:${chrome.headerCellText};letter-spacing:-0.02em">Payment receipt</div>
      <div style="font-size:11px;color:#6b7280;margin-top:4px">Confirmation of payment · ${escapeHtml(data.org.name)}</div>
    </td>
  </tr>
  <tr>
    <td style="padding-top:14px;padding-bottom:18px">
      <div style="border:1px solid ${chrome.datesBorder};background:${chrome.datesBg};border-radius:3px;padding:12px 14px;font-size:12px;line-height:1.55;color:#4b5563">
        <div><strong style="color:#111827">Invoice:</strong> ${escapeHtml(data.invoice.number)}</div>
        <div><strong style="color:#111827">Receipt number:</strong> ${escapeHtml(rcptNum)}</div>
        <div><strong style="color:#111827">Paid on:</strong> ${escapeHtml(paidDate)}</div>
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
        <div style="font-size:10px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.06em;margin-bottom:6px">Payment received</div>
        <div style="font-size:16px;font-weight:700;color:#111827">${escapeHtml(fmt(data.payment.amount, data.payment.currency))} · ${escapeHtml(paidDate)}</div>
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
            <table role="presentation" width="260" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-spacing:0">
              <tr>
                <td style="padding:0;margin:0">
                  <table role="presentation" width="260" cellpadding="0" cellspacing="0" style="border-collapse:separate;border-top:1px solid ${chrome.totalsBorder};border-left:1px solid ${chrome.totalsBorder};border-right:1px solid ${chrome.totalsBorder};border-radius:3px 3px 0 0;overflow:hidden">
                    <tr>
                      <td style="padding:6px 12px;font-size:12px;color:#6b7280;border-bottom:1px solid ${chrome.totalRowBorder}">Subtotal</td>
                      <td style="padding:6px 12px;text-align:right;font-size:12px;color:#1a1a1a;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(fmt(data.invoice.subtotal, data.invoice.currency))}</td>
                    </tr>
                    <tr>
                      <td style="padding:6px 12px;font-size:12px;color:#6b7280;border-bottom:1px solid ${chrome.totalRowBorder}">Total</td>
                      <td style="padding:6px 12px;text-align:right;font-size:12px;color:#1a1a1a;border-bottom:1px solid ${chrome.totalRowBorder}">${escapeHtml(fmt(data.invoice.total, data.invoice.currency))}</td>
                    </tr>
                  </table>
                </td>
              </tr>
              <tr>
                <td style="padding:0;margin:0">
                  <table role="presentation" width="260" cellpadding="0" cellspacing="0" style="border-collapse:separate;background:${chrome.grandBg};border-left:1px solid ${chrome.grandBg};border-right:1px solid ${chrome.grandBg};border-bottom:1px solid ${chrome.grandBg};border-radius:0 0 3px 3px">
                    <tr>
                      <td style="padding:9px 12px;font-size:13px;font-weight:700;color:#ffffff">Amount paid</td>
                      <td style="padding:9px 12px;text-align:right;font-size:13px;font-weight:700;color:#ffffff">${escapeHtml(fmt(data.payment.amount, data.payment.currency))}</td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </table>
    </td>
  </tr>
  <tr>
    <td style="padding-top:24px">
      <div style="font-size:11px;font-weight:700;color:${chrome.headerCellText};margin-bottom:8px;text-transform:uppercase;letter-spacing:0.05em">Payment record</div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse">
        <thead>
          <tr style="background:${chrome.tableHeaderBg}">
            <th style="text-align:left;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Method</th>
            <th style="text-align:left;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Date</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">Amount</th>
            <th style="text-align:right;padding:8px 10px;font-size:9px;font-weight:700;color:${chrome.headerCellText};text-transform:uppercase;letter-spacing:0.04em">REF</th>
          </tr>
        </thead>
        <tbody>
          <tr style="border-bottom:1px solid ${chrome.tableRowBorder}">
            <td style="padding:10px;font-size:12px;color:#111827">${escapeHtml(methodLabel)}</td>
            <td style="padding:10px;font-size:12px;color:#4b5563">${escapeHtml(paidDate)}</td>
            <td style="padding:10px;text-align:right;font-size:12px;font-weight:600">${escapeHtml(fmt(data.payment.amount, data.payment.currency))}</td>
            <td style="padding:10px;text-align:right;font-size:11px;font-family:ui-monospace,monospace;color:#4b5563">${escapeHtml(payRef)}</td>
          </tr>
        </tbody>
      </table>
    </td>
  </tr>
  <tr>
    <td style="padding-top:28px;border-top:1px solid #e5e7eb">
      <div style="text-align:right;font-size:11px;color:#9ca3af">Issued by ${escapeHtml(data.org.name)} · Thank you for your business</div>
    </td>
  </tr>
</table>`.trim();
}
