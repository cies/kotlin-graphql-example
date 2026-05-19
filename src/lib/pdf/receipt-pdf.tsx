import React from "react";
import {
  Document,
  Page,
  Text,
  View,
  Image,
  StyleSheet,
  renderToBuffer,
} from "@react-pdf/renderer";
import type { Currency, InvoicePdfFont, PaymentMethod } from "@prisma/client";
import { fetchAndConvertLogo } from "@/lib/branding/logo-png";
import {
  buildCompanyAddress,
  formatBillingAddress,
  formatCurrency as formatCurrencyValue,
  normalizeNumberFormatStyle,
  type NumberFormatStyle,
} from "@/lib/utils/format";
import {
  formatReceiptLongDate,
  formatReceiptPeriodRange,
  paymentReferenceDisplay,
  qtyLabelForReceiptLine,
  RECEIPT_METHOD_LABELS,
  resolveReceiptDisplayNumber,
  splitAddressBlock,
} from "@/lib/receipt/receipt-shared";
import { resolveCorporateChrome } from "@/lib/pdf/invoice-templates/corporate-tones";
import {
  INVOICE_LINE_ITEM_DESC_COLOR,
  INVOICE_LINE_ITEM_NAME_SIZE,
  INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
} from "@/lib/pdf/invoice-templates/types";
import {
  invoicePdfBold,
  invoicePdfFont,
  invoicePdfLineItemNameStyle,
  prepareInvoicePdfFonts,
} from "@/lib/pdf/invoice-font";

const TEXT = "#111827";
const GREY = "#374151";
const MUTED = "#6b7280";

function formatCurrency(amount: string | number, currency: Currency, style?: NumberFormatStyle): string {
  return formatCurrencyValue(amount, currency, normalizeNumberFormatStyle(style));
}

export interface ReceiptLinePdf {
  name: string;
  description?: string | null;
  quantity: string;
  qtyType: string;
  unitPrice: string;
  total: string;
}

export interface ReceiptPdfData {
  org: {
    name: string;
    addressBlock?: string | null;
    vat?: string | null;
    logoUrl?: string | null;
    email?: string | null;
    phone?: string | null;
  };
  customer: {
    name: string;
    email?: string | null;
    billingAddressBlock?: string | null;
  };
  invoice: {
    number: string;
    currency: Currency;
    subtotal: string;
    vat: string;
    total: string;
    periodFrom?: Date | string | null;
    periodTo?: Date | string | null;
  };
  lines: ReceiptLinePdf[];
  payment: {
    receiptNumber?: string | null;
    amount: string;
    currency: Currency;
    method: PaymentMethod;
    paidAt: Date;
    stripeChargeId?: string | null;
    notes?: string | null;
  };
  /** Prefer `invoiceAccentColor`, then email accent - drives Corporate-style chrome */
  accentColor?: string | null;
  paymentId?: string | null;
  logoPngBase64?: string;
  numberFormatStyle?: NumberFormatStyle;
  /** Same org setting as invoice PDFs; defaults in `buildReceiptPdfDataFromPayment` / `generateReceiptPdf`. */
  invoicePdfFont?: InvoicePdfFont;
}

type ReceiptPaymentSlice = {
  id?: string;
  amount: string | number | { toString(): string };
  currency: Currency;
  method: PaymentMethod;
  paidAt: Date;
  stripeChargeId?: string | null;
  notes?: string | null;
  receiptNumber?: string | null;
};

type ReceiptCustomerSlice = {
  companyName?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  billingAddress?: unknown;
};

type InvoiceLineSlice = {
  name: string;
  description?: string | null;
  quantity: { toString(): string };
  qtyType: string;
  unitPrice: { toString(): string };
  total: { toString(): string };
};

export function buildReceiptPdfDataFromPayment(params: {
  payment: ReceiptPaymentSlice;
  invoice: {
    number: string;
    currency: Currency;
    subtotal: string | number | { toString(): string };
    vat: string | number | { toString(): string };
    total: string | number | { toString(): string };
    periodFrom?: Date | string | null;
    periodTo?: Date | string | null;
    customer: ReceiptCustomerSlice;
    lines: InvoiceLineSlice[];
  };
  org: {
    name: string;
    settings?: {
      companyName?: string | null;
      companyAddress?: string | null;
      companyCity?: string | null;
      companyState?: string | null;
      companyCountry?: string | null;
      companyPostalCode?: string | null;
      companyVat?: string | null;
      companyLogoUrl?: string | null;
      smtpFrom?: string | null;
      companyPhone?: string | null;
      numberFormatStyle?: string | null;
      invoiceAccentColor?: string | null;
      emailAccentColor?: string | null;
      invoicePdfFont?: InvoicePdfFont | null;
    } | null;
  };
}): ReceiptPdfData {
  const { payment, invoice, org } = params;
  const settings = org.settings ?? null;
  const c = invoice.customer;
  const customerName =
    c.companyName ??
    [c.firstName, c.lastName].filter(Boolean).join(" ") ??
    c.email ??
    "Customer";

  const amountStr =
    typeof payment.amount === "string" || typeof payment.amount === "number"
      ? String(payment.amount)
      : payment.amount.toString();
  const subtotalStr =
    typeof invoice.subtotal === "string" || typeof invoice.subtotal === "number"
      ? String(invoice.subtotal)
      : invoice.subtotal.toString();
  const vatStr =
    typeof invoice.vat === "string" || typeof invoice.vat === "number"
      ? String(invoice.vat)
      : invoice.vat.toString();
  const totalStr =
    typeof invoice.total === "string" || typeof invoice.total === "number"
      ? String(invoice.total)
      : invoice.total.toString();

  const orgName = settings?.companyName ?? org.name;
  const addressBlock = buildCompanyAddress(settings ?? {});

  const accent =
    settings?.invoiceAccentColor?.trim() || settings?.emailAccentColor?.trim() || null;

  return {
    org: {
      name: orgName,
      addressBlock: addressBlock ?? null,
      vat: settings?.companyVat,
      logoUrl: settings?.companyLogoUrl,
      email: settings?.smtpFrom,
      phone: settings?.companyPhone?.trim() || null,
    },
    customer: {
      name: customerName,
      email: c.email,
      billingAddressBlock: formatBillingAddress(c.billingAddress) ?? null,
    },
    invoice: {
      number: invoice.number,
      currency: invoice.currency,
      subtotal: subtotalStr,
      vat: vatStr,
      total: totalStr,
      periodFrom: invoice.periodFrom,
      periodTo: invoice.periodTo,
    },
    lines: invoice.lines.map((line) => ({
      name: line.name,
      description: line.description,
      quantity: line.quantity.toString(),
      qtyType: line.qtyType,
      unitPrice: line.unitPrice.toString(),
      total: line.total.toString(),
    })),
    payment: {
      receiptNumber: payment.receiptNumber,
      amount: amountStr,
      currency: payment.currency,
      method: payment.method,
      paidAt: payment.paidAt,
      stripeChargeId: payment.stripeChargeId ?? undefined,
      notes: payment.notes ?? undefined,
    },
    accentColor: accent,
    paymentId: payment.id ?? null,
    numberFormatStyle: normalizeNumberFormatStyle(settings?.numberFormatStyle),
    invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
  };
}

export function ReceiptPdfDocument({ data }: { data: ReceiptPdfData }) {
  const { org, customer, invoice, payment, logoPngBase64 } = data;
  const nf = data.numberFormatStyle;
  const chrome = resolveCorporateChrome(data.accentColor);
  const S = StyleSheet.create({
    page: {
      backgroundColor: "#ffffff",
      fontFamily: invoicePdfFont(),
      fontSize: 10,
      color: TEXT,
      paddingTop: 20,
      paddingBottom: 28,
      paddingHorizontal: 20,
    },
    logo: { width: 168, height: 56, objectFit: "contain" },
    title: { fontSize: 24, ...invoicePdfBold(), color: chrome.headerCellText, marginBottom: 2 },
    subtitle: { fontSize: 9, color: MUTED, marginBottom: 10 },
    headerRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 10 },
    metaBox: {
      borderWidth: 1,
      borderColor: chrome.datesBorder,
      backgroundColor: chrome.datesBg,
      borderRadius: 3,
      paddingVertical: 10,
      paddingHorizontal: 14,
      marginBottom: 16,
    },
    metaLine: { fontSize: 9, color: GREY, marginBottom: 3 },
    metaStrong: { ...invoicePdfBold(), color: TEXT },
    twoCol: { flexDirection: "row", marginBottom: 16 },
    fromCol: { flex: 1, paddingRight: 12 },
    toCol: { flex: 1, paddingLeft: 24, borderLeftWidth: 1, borderLeftColor: "#e5e7eb" },
    ftHeading: { fontSize: 10, ...invoicePdfBold(), color: chrome.headerCellText, marginBottom: 4 },
    ftLine: { fontSize: 9, color: GREY, marginBottom: 2 },
    receivedBand: {
      backgroundColor: chrome.tableHeaderBg,
      borderLeftWidth: 4,
      borderLeftColor: chrome.grandBg,
      paddingVertical: 11,
      paddingHorizontal: 14,
      marginBottom: 14,
      borderRadius: 3,
    },
    receivedTitle: {
      fontSize: 8,
      ...invoicePdfBold(),
      color: chrome.headerCellText,
      textTransform: "uppercase",
      marginBottom: 4,
    },
    receivedAmount: { fontSize: 14, ...invoicePdfBold(), color: TEXT },
    tableHeadRow: {
      flexDirection: "row",
      backgroundColor: chrome.tableHeaderBg,
      paddingVertical: 7,
      paddingHorizontal: 10,
    },
    th: { fontSize: 8, ...invoicePdfBold(), color: chrome.headerCellText, textTransform: "uppercase" },
    tableRow: {
      flexDirection: "row",
      alignItems: "flex-start",
      paddingVertical: 7,
      paddingHorizontal: 10,
      borderBottomWidth: 1,
      borderBottomColor: chrome.tableRowBorder,
    },
    rowAlt: { backgroundColor: chrome.tableRowAltBg },
    tdDesc: {
      fontSize: INVOICE_LINE_ITEM_NAME_SIZE,
      color: INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    tdQty: { flex: 1, fontSize: 9, color: GREY, textAlign: "right", ...invoicePdfLineItemNameStyle() },
    tdUnit: { flex: 1, fontSize: 9, color: GREY, textAlign: "right", ...invoicePdfLineItemNameStyle() },
    tdAmt: { flex: 1, fontSize: 9, textAlign: "right", color: TEXT, ...invoicePdfLineItemNameStyle() },
    tdSub: { fontSize: 8, color: INVOICE_LINE_ITEM_DESC_COLOR, marginTop: 2 },
    sectionTitle: { fontSize: 10, ...invoicePdfBold(), color: chrome.headerCellText, marginTop: 18, marginBottom: 6 },
    totalsWrap: { flexDirection: "row", justifyContent: "flex-end", marginTop: 12 },
    totalsColumn: { width: 260 },
    totalsBoxUpper: {
      width: 260,
      borderTopWidth: 1,
      borderLeftWidth: 1,
      borderRightWidth: 1,
      borderTopColor: chrome.totalsBorder,
      borderLeftColor: chrome.totalsBorder,
      borderRightColor: chrome.totalsBorder,
      borderTopLeftRadius: 3,
      borderTopRightRadius: 3,
      overflow: "hidden",
    },
    totalRow: {
      flexDirection: "row",
      paddingVertical: 6,
      paddingHorizontal: 12,
      borderBottomWidth: 1,
      borderBottomColor: chrome.totalRowBorder,
    },
    totalLabel: { flex: 1, fontSize: 9, color: MUTED, ...invoicePdfLineItemNameStyle() },
    totalVal: { width: 90, fontSize: 9, textAlign: "right", color: TEXT, ...invoicePdfLineItemNameStyle() },
    grandRow: {
      flexDirection: "row",
      paddingVertical: 9,
      paddingHorizontal: 12,
      backgroundColor: chrome.grandBg,
      borderLeftWidth: 1,
      borderRightWidth: 1,
      borderBottomWidth: 1,
      borderLeftColor: chrome.grandBg,
      borderRightColor: chrome.grandBg,
      borderBottomColor: chrome.grandBg,
      borderBottomLeftRadius: 3,
      borderBottomRightRadius: 3,
      width: 260,
    },
    grandLabel: { flex: 1, fontSize: 10, ...invoicePdfBold(), color: "#ffffff" },
    grandVal: { width: 90, fontSize: 10, ...invoicePdfBold(), textAlign: "right", color: "#ffffff" },
    footer: { marginTop: 22, paddingTop: 12, borderTopWidth: 1, borderTopColor: "#e5e7eb" },
    footerText: { fontSize: 8, color: MUTED, textAlign: "right" },
  });

  const rcptNum = resolveReceiptDisplayNumber(
    data.payment.receiptNumber,
    invoice.number,
    data.paymentId ?? undefined,
    payment.paidAt
  );
  const paidDateStr = formatReceiptLongDate(payment.paidAt);
  const invPeriod = formatReceiptPeriodRange(invoice.periodFrom, invoice.periodTo);
  const methodLabel = RECEIPT_METHOD_LABELS[payment.method];
  const payRef = paymentReferenceDisplay(payment.stripeChargeId, payment.notes);

  const displayLines =
    data.lines.length > 0
      ? data.lines
      : [
          {
            name: `Invoice ${invoice.number}`,
            description: null,
            quantity: "1",
            qtyType: "QTY",
            unitPrice: payment.amount,
            total: payment.amount,
          },
        ];

  const orgLines = splitAddressBlock(org.addressBlock);
  const custAddr = splitAddressBlock(customer.billingAddressBlock);

  return (
    <Document>
      <Page size="A4" style={S.page}>
        <View style={S.headerRow}>
          <View>
            <Text style={S.title}>Payment receipt</Text>
            <Text style={S.subtitle}>Confirmation of payment · {org.name}</Text>
          </View>
          {logoPngBase64 ? (
            <Image style={S.logo} src={`data:image/png;base64,${logoPngBase64}`} />
          ) : null}
        </View>

        <View style={S.metaBox}>
          <Text style={S.metaLine}>
            <Text style={S.metaStrong}>Invoice: </Text>
            {invoice.number}
          </Text>
          <Text style={S.metaLine}>
            <Text style={S.metaStrong}>Receipt number: </Text>
            {rcptNum}
          </Text>
          <Text style={S.metaLine}>
            <Text style={S.metaStrong}>Paid on: </Text>
            {paidDateStr}
          </Text>
        </View>

        <View style={S.twoCol}>
          <View style={S.fromCol}>
            <Text style={S.ftHeading}>From</Text>
            <Text style={[S.ftLine, { ...invoicePdfBold(), color: TEXT }]}>{org.name}</Text>
            {orgLines.map((line, i) => (
              <Text key={`o-${i}`} style={S.ftLine}>
                {line}
              </Text>
            ))}
            {org.phone ? <Text style={S.ftLine}>{org.phone}</Text> : null}
            {org.vat ? (
              <Text style={S.ftLine}>
                VAT: {org.vat}
              </Text>
            ) : null}
          </View>
          <View style={S.toCol}>
            <Text style={S.ftHeading}>Bill to</Text>
            <Text style={[S.ftLine, { ...invoicePdfBold(), color: TEXT }]}>{customer.name}</Text>
            {customer.email ? <Text style={S.ftLine}>{customer.email}</Text> : null}
            {custAddr.map((line, i) => (
              <Text key={`c-${i}`} style={S.ftLine}>
                {line}
              </Text>
            ))}
          </View>
        </View>

        <View style={S.receivedBand}>
          <Text style={S.receivedTitle}>Payment received</Text>
          <Text style={S.receivedAmount}>
            {formatCurrency(payment.amount, payment.currency, nf)} · {paidDateStr}
          </Text>
        </View>

        <View style={S.tableHeadRow}>
          <Text style={[S.th, { flex: 3 }]}>Item / Service</Text>
          <Text style={[S.th, { flex: 1, textAlign: "right" }]}>Qty</Text>
          <Text style={[S.th, { flex: 1, textAlign: "right" }]}>Unit price</Text>
          <Text style={[S.th, { flex: 1, textAlign: "right" }]}>Amount</Text>
        </View>

        {displayLines.map((line, idx) => {
          const qty = qtyLabelForReceiptLine(line.quantity, line.qtyType);
          const subParts: string[] = [];
          if (line.description?.trim()) subParts.push(line.description.trim());
          if (idx === 0 && invPeriod) subParts.push(invPeriod);
          const sub = subParts.length ? subParts.join("\n") : null;
          const rowStyle = idx % 2 === 1 ? [S.tableRow, S.rowAlt] : S.tableRow;
          return (
            <View key={idx} style={rowStyle}>
              <View style={{ flex: 3 }}>
                <Text style={S.tdDesc}>{line.name}</Text>
                {sub ? <Text style={S.tdSub}>{sub}</Text> : null}
              </View>
              <Text style={S.tdQty}>{qty}</Text>
              <Text style={S.tdUnit}>{formatCurrency(line.unitPrice, invoice.currency, nf)}</Text>
              <Text style={S.tdAmt}>{formatCurrency(line.total, invoice.currency, nf)}</Text>
            </View>
          );
        })}

        <View style={S.totalsWrap}>
          <View style={S.totalsColumn}>
            <View style={S.totalsBoxUpper}>
              <View style={S.totalRow}>
                <Text style={S.totalLabel}>Subtotal</Text>
                <Text style={S.totalVal}>{formatCurrency(invoice.subtotal, invoice.currency, nf)}</Text>
              </View>
              <View style={S.totalRow}>
                <Text style={S.totalLabel}>Total</Text>
                <Text style={S.totalVal}>{formatCurrency(invoice.total, invoice.currency, nf)}</Text>
              </View>
            </View>
            <View style={S.grandRow}>
              <Text style={S.grandLabel}>Amount paid</Text>
              <Text style={S.grandVal}>{formatCurrency(payment.amount, payment.currency, nf)}</Text>
            </View>
          </View>
        </View>

        <Text style={S.sectionTitle}>Payment record</Text>
        <View style={S.tableHeadRow}>
          <Text style={[S.th, { flex: 2 }]}>Method</Text>
          <Text style={[S.th, { flex: 1.3 }]}>Date</Text>
          <Text style={[S.th, { flex: 1.2, textAlign: "right" }]}>Amount</Text>
          <Text style={[S.th, { flex: 1.35, textAlign: "right" }]}>REF</Text>
        </View>
        <View style={S.tableRow}>
          <Text style={{ flex: 2, fontSize: 9, color: TEXT }}>{methodLabel}</Text>
          <Text style={{ flex: 1.3, fontSize: 9, color: GREY }}>{paidDateStr}</Text>
          <Text style={{ flex: 1.2, fontSize: 9, textAlign: "right", ...invoicePdfBold() }}>
            {formatCurrency(payment.amount, payment.currency, nf)}
          </Text>
          <Text style={{ flex: 1.35, fontSize: 8, fontFamily: "Courier", color: GREY, textAlign: "right" }}>
            {payRef}
          </Text>
        </View>

        <View style={S.footer}>
          <Text style={S.footerText}>
            Issued by {org.name} · Thank you for your business
          </Text>
        </View>
      </Page>
    </Document>
  );
}

export async function generateReceiptPdf(data: ReceiptPdfData): Promise<Buffer> {
  prepareInvoicePdfFonts(data.invoicePdfFont ?? "HELVETICA");

  let logoPngBase64: string | undefined;
  if (data.org.logoUrl) {
    try {
      const pngBuf = await fetchAndConvertLogo(data.org.logoUrl);
      logoPngBase64 = pngBuf.toString("base64");
    } catch {
      // Logo unavailable
    }
  }

  const dataWithLogo: ReceiptPdfData = { ...data, logoPngBase64 };
  const buffer = await renderToBuffer(<ReceiptPdfDocument data={dataWithLogo} />);
  return Buffer.from(buffer);
}
