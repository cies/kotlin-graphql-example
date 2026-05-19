import React from "react";
import { Document, Page, Text, View, StyleSheet, Image } from "@react-pdf/renderer";
import {
  formatCurrencyWithStyle,
  formatCurrencyWithStyleAndCode,
  formatDate,
  formatQtyDisplay,
  formatVatLabel,
  formatDiscountLabel,
  STATUS_COLORS,
  getInvoiceDisplayStatus,
  splitNonEmptyLines,
  INVOICE_LINE_ITEM_NAME_COLOR,
  INVOICE_LINE_ITEM_DESC_COLOR,
  INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
  INVOICE_LINE_ITEM_NAME_SIZE,
  INVOICE_LINE_ITEM_QTY_PRICE_SIZE,
  INVOICE_LINE_ITEM_TOTAL_SIZE,
  type InvoicePdfData,
} from "./types";
import { invoicePdfBold, invoicePdfFont, invoicePdfLineItemNameStyle } from "../invoice-font";

/**
 * Modern template - bold accent rule, light strip, charcoal table header, strong footer.
 */
export function ModernInvoiceDocument({ data }: { data: InvoicePdfData }) {
  const accent = data.accentColor ?? "#7c3aed";
  const { org, invoice, customer, lines } = data;
  const amountDue = (parseFloat(invoice.total) - parseFloat(invoice.amountPaid)).toFixed(2);
  const displayStatus = getInvoiceDisplayStatus(invoice.status, invoice.displayStatus);

  const styles = StyleSheet.create({
    page: {
      padding: 24, paddingBottom: 42,
      fontFamily: invoicePdfFont(), fontSize: 10,
      color: "#1a1a1a", backgroundColor: "#ffffff",
    },

    // Fixed repeating header
    pageHeader: {
      flexDirection: "row", justifyContent: "space-between",
      alignItems: "flex-start", marginBottom: 10,
    },
    logo: { width: 110, height: 46, objectFit: "contain", marginBottom: 6 },
    orgName: { fontSize: 16, ...invoicePdfBold(), color: "#111827", marginBottom: 3 },
    orgDetail: { fontSize: 8, color: "#555", marginBottom: 2 },
    headerRight: { alignItems: "flex-end" },
    // "INVOICE" in large accent-coloured text - dark against white background
    invoiceWord: { fontSize: 28, ...invoicePdfBold(), color: accent },
    invoiceNum: { fontSize: 10, color: "#374151", marginTop: 4 },
    statusBadge: { marginTop: 8, alignSelf: "flex-end", fontSize: 8, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4 },

    // Thick accent rule + hairline separator
    rule: { height: 3, backgroundColor: accent, marginBottom: 2 },
    subRule: { height: 1, backgroundColor: "#e9d5ff", marginBottom: 20 },

    // ── Bill-to + dates strip ──
    strip: {
      flexDirection: "row",
      backgroundColor: "#faf9ff",
      borderWidth: 1, borderColor: "#e9e4ff", borderRadius: 3,
      paddingHorizontal: 16, paddingVertical: 14,
      marginBottom: 22,
    },
    stripBlock: { flex: 1, paddingRight: 16 },
    stripLabel: {
      fontSize: 7, color: "#6b7280",
      textTransform: "uppercase", letterSpacing: 1,
      ...invoicePdfBold(), marginBottom: 5,
    },
    stripValue: { fontSize: 10, color: "#111827", marginBottom: 2 },
    stripSub: { fontSize: 8, color: "#555", marginBottom: 1 },

    // ── Table ──
    // Dark header bar - derived from accent for brand alignment; white text
    tableHeaderRow: {
      flexDirection: "row", paddingVertical: 7, paddingHorizontal: 10,
      backgroundColor: "#374151",
    },
    tableRow: {
      flexDirection: "row", paddingVertical: 8, paddingHorizontal: 10,
      borderBottomWidth: 1, borderBottomColor: "#f3f4f6",
    },
    tableRowAlt: { backgroundColor: "#fafafa" },
    colDesc: { flex: 3 },
    colQty: { flex: 1, textAlign: "right" },
    colPrice: { flex: 1, textAlign: "right" },
    colTotal: { flex: 1, textAlign: "right" },
    lineItemName: {
      fontSize: INVOICE_LINE_ITEM_NAME_SIZE,
      color: INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    lineItemDesc: { fontSize: 8, color: INVOICE_LINE_ITEM_DESC_COLOR, marginTop: 2 },
    lineQty: {
      fontSize: INVOICE_LINE_ITEM_QTY_PRICE_SIZE,
      color: INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    linePrice: {
      fontSize: INVOICE_LINE_ITEM_QTY_PRICE_SIZE,
      color: INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    lineTotal: {
      fontSize: INVOICE_LINE_ITEM_TOTAL_SIZE,
      color: INVOICE_LINE_ITEM_NAME_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    // White text on dark slate (#374151) - always readable
    headerText: { fontSize: 9, ...invoicePdfBold(), color: "#ffffff" },

    // ── Totals - white bg, accent only for the grand total border + text ──
    totalsWrap: { flexDirection: "row", justifyContent: "flex-end", marginTop: 14 },
    totalsBox: { width: 250 },
    totalRow: {
      flexDirection: "row", paddingVertical: 6,
      borderBottomWidth: 1, borderBottomColor: "#f0f0f0",
    },
    totalLabel: { flex: 1, color: "#6b7280" },
    totalValue: { width: 90, textAlign: "right", color: "#111827" },
    grandRow: {
      flexDirection: "row", paddingTop: 8, paddingBottom: 2,
      borderTopWidth: 2, borderTopColor: accent,
    },
    grandLabel: { flex: 1, ...invoicePdfBold(), fontSize: 12, color: accent },
    grandValue: { width: 90, textAlign: "right", ...invoicePdfBold(), fontSize: 12, color: accent },

    // ── Notes ──
    notesWrap: { marginTop: 28 },
    notesLabel: {
      fontSize: 7, color: "#6b7280",
      textTransform: "uppercase", letterSpacing: 1,
      ...invoicePdfBold(), marginBottom: 5,
    },
    notesText: { fontSize: 9, color: "#4b5563", lineHeight: 1.5 },

    // ── Footer - dark charcoal, white text ──
    footer: {
      position: "absolute", bottom: 0, left: 0, right: 0,
      height: 30, backgroundColor: "#1f2937",
      flexDirection: "row", justifyContent: "space-between", alignItems: "center",
      paddingHorizontal: 24,
    },
    footerText: { fontSize: 8, color: "#9ca3af" },
  });

  const vatLabel = formatVatLabel(invoice.vatRate, invoice.vatIncluded);
  const hasDiscount = invoice.discountType && invoice.discountType !== "NONE" && parseFloat(invoice.discount ?? "0") > 0;
  const discountLabel = formatDiscountLabel(invoice.discountType, undefined, invoice.discountBeforeTax);
  const orgContactLines = splitNonEmptyLines(org.phoneContact);
  const orgAddressLines = splitNonEmptyLines(org.address);
  const customerAddressLines = splitNonEmptyLines(customer.address);

  return (
    <Document>
      <Page size="A4" style={styles.page}>
        {/* === Fixed header - repeats on every page === */}
        <View fixed style={styles.pageHeader}>
          <View>
            {data.logoPngBase64 ? (
              <Image src={`data:image/png;base64,${data.logoPngBase64}`} style={styles.logo} />
            ) : (
              <Text style={styles.orgName}>{org.name}</Text>
            )}
            {orgAddressLines.map((line, i) => (
              <Text key={`oa-${i}`} style={styles.orgDetail}>{line}</Text>
            ))}
            {org.vat && <Text style={styles.orgDetail}>VAT ID: {org.vat}</Text>}
            {orgContactLines.map((line, i) => (
              <Text key={`oc-${i}`} style={styles.orgDetail}>{line}</Text>
            ))}
            {org.billingEmail && <Text style={styles.orgDetail}>{org.billingEmail}</Text>}
          </View>
          <View style={styles.headerRight}>
            <Text style={styles.invoiceWord}>INVOICE</Text>
            <Text style={styles.invoiceNum}>{invoice.number}</Text>
            {invoice.showStatus !== false && (
              <View style={[styles.statusBadge, { backgroundColor: STATUS_COLORS[invoice.status] ?? "#e5e7eb" }]}>
                <Text style={{ fontSize: 8, color: "#1f2937" }}>{displayStatus}</Text>
              </View>
            )}
          </View>
        </View>

        {/* Accent rules */}
        <View style={styles.rule} />
        <View style={styles.subRule} />

        {/* ── Info strip ── */}
        <View style={styles.strip}>
          <View style={styles.stripBlock}>
            <Text style={styles.stripValue}>
              <Text>To: </Text>
              <Text style={invoicePdfBold()}>{customer.name}</Text>
            </Text>
            {customer.vat && <Text style={styles.stripSub}>VAT / Tax ID: {customer.vat}</Text>}
            {customerAddressLines.map((line, i) => (
              <Text key={`ca-${i}`} style={styles.stripSub}>{line}</Text>
            ))}
            {customer.phone && <Text style={styles.stripSub}>Phone: {customer.phone}</Text>}
            {customer.email && <Text style={styles.stripSub}>Email: {customer.email}</Text>}
          </View>
          <View style={[styles.stripBlock, { flex: 0.55 }]}>
            <Text style={styles.stripLabel}>Issue Date</Text>
            <Text style={styles.stripValue}>{formatDate(invoice.issuedAt)}</Text>
            <Text style={[styles.stripLabel, { marginTop: 8 }]}>Due Date</Text>
            <Text style={styles.stripValue}>{formatDate(invoice.dueDate)}</Text>
          </View>
          {(invoice.periodFrom || invoice.periodTo) && (
            <View style={[styles.stripBlock, { flex: 0.55, paddingRight: 0 }]}>
              <Text style={styles.stripLabel}>Invoiced Period</Text>
              <Text style={styles.stripValue}>
                {formatDate(invoice.periodFrom)} – {formatDate(invoice.periodTo)}
              </Text>
            </View>
          )}
        </View>

        {/* ── Table ── */}
        <View style={styles.tableHeaderRow}>
          <Text style={[styles.headerText, styles.colDesc]}>Item / Service</Text>
          <Text style={[styles.headerText, styles.colQty]}>Qty</Text>
          <Text style={[styles.headerText, styles.colPrice]}>Unit Price</Text>
          <Text style={[styles.headerText, styles.colTotal]}>Total</Text>
        </View>
        {lines.map((line, i) => (
          <View key={i} style={[styles.tableRow, i % 2 === 1 ? styles.tableRowAlt : {}]}>
            <View style={styles.colDesc}>
              <Text style={styles.lineItemName}>{line.name}</Text>
              {!!line.description && <Text style={styles.lineItemDesc}>{line.description}</Text>}
            </View>
            <Text style={[styles.colQty, styles.lineQty]}>{formatQtyDisplay(line.quantity, line.qtyType)}</Text>
            <Text style={[styles.colPrice, styles.linePrice]}>
              {formatCurrencyWithStyle(line.unitPrice, invoice.currency, data.numberFormatStyle)}
            </Text>
            <Text style={[styles.colTotal, styles.lineTotal]}>
              {formatCurrencyWithStyle(line.total, invoice.currency, data.numberFormatStyle)}
            </Text>
          </View>
        ))}

        {/* ── Totals ── */}
        <View style={styles.totalsWrap}>
          <View style={styles.totalsBox}>
            <View style={styles.totalRow}>
              <Text style={[styles.totalLabel, invoicePdfLineItemNameStyle()]}>Subtotal</Text>
              <Text style={[styles.totalValue, invoicePdfLineItemNameStyle()]}>
                {formatCurrencyWithStyle(invoice.subtotal, invoice.currency, data.numberFormatStyle)}
              </Text>
            </View>
            {hasDiscount && invoice.discountBeforeTax && (
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, { color: "#16a34a" }]}>{discountLabel}</Text>
                <Text style={[styles.totalValue, { color: "#16a34a" }]}>-{formatCurrencyWithStyle(invoice.discount ?? "0", invoice.currency, data.numberFormatStyle)}</Text>
              </View>
            )}
            <View style={styles.totalRow}>
              <Text style={[styles.totalLabel, invoicePdfLineItemNameStyle()]}>{vatLabel}</Text>
              <Text style={[styles.totalValue, invoicePdfLineItemNameStyle()]}>
                {formatCurrencyWithStyle(invoice.vat, invoice.currency, data.numberFormatStyle)}
              </Text>
            </View>
            {hasDiscount && !invoice.discountBeforeTax && (
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, { color: "#16a34a" }]}>{discountLabel}</Text>
                <Text style={[styles.totalValue, { color: "#16a34a" }]}>-{formatCurrencyWithStyle(invoice.discount ?? "0", invoice.currency, data.numberFormatStyle)}</Text>
              </View>
            )}
            {parseFloat(invoice.amountPaid) > 0 && (
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, invoicePdfLineItemNameStyle()]}>Amount Paid</Text>
                <Text style={[styles.totalValue, invoicePdfLineItemNameStyle()]}>
                  {formatCurrencyWithStyle(invoice.amountPaid, invoice.currency, data.numberFormatStyle)}
                </Text>
              </View>
            )}
            <View style={styles.grandRow}>
              <Text style={styles.grandLabel}>
                {parseFloat(invoice.amountPaid) > 0 ? "Amount Due" : "Total Due"}
              </Text>
              <Text style={styles.grandValue}>
                {formatCurrencyWithStyleAndCode(
                  parseFloat(invoice.amountPaid) > 0 ? amountDue : invoice.total,
                  invoice.currency,
                  data.numberFormatStyle
                )}
              </Text>
            </View>
          </View>
        </View>

        {/* ── T&C ── */}
        {invoice.termsAndConditions && (
          <View style={styles.notesWrap}>
            <Text style={styles.notesLabel}>Terms &amp; Conditions</Text>
            <Text style={styles.notesText}>{invoice.termsAndConditions}</Text>
          </View>
        )}

        {/* ── Notes ── */}
        {(invoice.notes || data.footerText) && (
          <View style={styles.notesWrap}>
            {invoice.notes && (
              <>
                <Text style={styles.notesLabel}>Notes</Text>
                <Text style={styles.notesText}>{invoice.notes}</Text>
              </>
            )}
            {data.footerText && (
              <Text style={[styles.notesText, { marginTop: invoice.notes ? 8 : 0 }]}>{data.footerText}</Text>
            )}
          </View>
        )}

        {/* ── Footer ── */}
        <View style={styles.footer}>
          <Text style={styles.footerText}>{org.name}</Text>
          <Text style={styles.footerText}>Generated {new Date().toLocaleDateString("en-GB")}</Text>
        </View>
      </Page>
    </Document>
  );
}
