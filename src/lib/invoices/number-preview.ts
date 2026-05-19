import {
  expandDocNumberPrefix,
  hasDocNumberTemplateTokens,
  prefixContainsRandomToken,
} from "@/lib/invoices/number-template";

function previewMockRandom(len: number): string {
  const alphabet = "X7K2M4N9P3Q8ABCDEFGHJK";
  let s = "";
  for (let i = 0; i < len; i++) s += alphabet[i % alphabet.length]!;
  return s;
}

type InvoicePreviewForm = {
  invoiceNumberPrefix: string;
  invoiceNumberFormat: string;
  invoiceNumberPadding: number | string;
  invoiceNumberRandomLength: number | string;
};

/** Sample invoice number for settings UI and new-invoice form (not cryptographically random). */
export function previewInvoiceDocNumber(
  form: InvoicePreviewForm,
  options?: { prefixOverride?: string }
): string {
  const now = new Date();
  const len = Math.min(
    32,
    Math.max(
      1,
      typeof form.invoiceNumberRandomLength === "string"
        ? parseInt(form.invoiceNumberRandomLength) || 6
        : form.invoiceNumberRandomLength
    )
  );
  const pad =
    typeof form.invoiceNumberPadding === "string"
      ? parseInt(form.invoiceNumberPadding) || 4
      : form.invoiceNumberPadding;
  const mock = previewMockRandom(len);
  const raw =
    options?.prefixOverride != null && options.prefixOverride.trim().length > 0
      ? options.prefixOverride.trim()
      : form.invoiceNumberPrefix || "INV";
  const prefix = raw;
  const fmt = form.invoiceNumberFormat;

  if (fmt === "DATE_RANDOM") {
    if (hasDocNumberTemplateTokens(prefix)) {
      if (prefixContainsRandomToken(prefix)) {
        return expandDocNumberPrefix(prefix, now, len, mock);
      }
      const body = expandDocNumberPrefix(prefix, now, len, "");
      return `${body}-${mock}`;
    }
    const y = String(now.getFullYear());
    const m = String(now.getMonth() + 1).padStart(2, "0");
    const d = String(now.getDate()).padStart(2, "0");
    return `${prefix}-${y}${m}${d}-${mock}`;
  }

  const seq = "1".padStart(pad, "0");
  if (!hasDocNumberTemplateTokens(prefix)) {
    if (fmt === "YEAR_SEQ") return `${prefix}-${now.getFullYear()}-${seq}`;
    if (fmt === "YEARMONTH_SEQ")
      return `${prefix}-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, "0")}-${seq}`;
    return `${prefix}-${seq}`;
  }
  const base = expandDocNumberPrefix(prefix, now, len, "");
  return `${base}-${seq}`;
}

type ReceiptPreviewForm = {
  receiptNumberPrefix: string;
  receiptNumberFormat: string;
  receiptNumberPadding: number | string;
  receiptNumberRandomLength: number | string;
};

export function previewReceiptDocNumber(form: ReceiptPreviewForm): string {
  const now = new Date();
  const len = Math.min(
    32,
    Math.max(
      1,
      typeof form.receiptNumberRandomLength === "string"
        ? parseInt(form.receiptNumberRandomLength) || 6
        : form.receiptNumberRandomLength
    )
  );
  const pad =
    typeof form.receiptNumberPadding === "string"
      ? parseInt(form.receiptNumberPadding) || 4
      : form.receiptNumberPadding;
  const mock = previewMockRandom(len);
  const prefix = form.receiptNumberPrefix || "REC";
  const fmt = form.receiptNumberFormat;

  if (fmt === "DATE_RANDOM") {
    if (hasDocNumberTemplateTokens(prefix)) {
      if (prefixContainsRandomToken(prefix)) {
        return expandDocNumberPrefix(prefix, now, len, mock);
      }
      const body = expandDocNumberPrefix(prefix, now, len, "");
      return `${body}-${mock}`;
    }
    const y = String(now.getFullYear());
    const m = String(now.getMonth() + 1).padStart(2, "0");
    const d = String(now.getDate()).padStart(2, "0");
    return `${prefix}-${y}${m}${d}-${mock}`;
  }

  const seq = "1".padStart(pad, "0");
  if (!hasDocNumberTemplateTokens(prefix)) {
    if (fmt === "YEAR_SEQ") return `${prefix}-${now.getFullYear()}-${seq}`;
    if (fmt === "YEARMONTH_SEQ")
      return `${prefix}-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, "0")}-${seq}`;
    return `${prefix}-${seq}`;
  }
  const base = expandDocNumberPrefix(prefix, now, len, "");
  return `${base}-${seq}`;
}
