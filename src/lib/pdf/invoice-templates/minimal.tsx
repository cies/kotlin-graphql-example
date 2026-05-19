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

export function MinimalInvoiceDocument({ data }: { data: InvoicePdfData }) {
  const accent = data.accentColor ?? "#0f172a";
  const { org, invoice, customer, lines } = data;
  const amountDue = (parseFloat(invoice.total) - parseFloat(invoice.amountPaid)).toFixed(2);
  const displayStatus = getInvoiceDisplayStatus(invoice.status, invoice.displayStatus);

  const styles = StyleSheet.create({
    page: { padding: 28, fontFamily: invoicePdfFont(), fontSize: 10, color: "#1e293b", backgroundColor: "#ffffff" },

    // Fixed repeating header
    pageHeader: {
      flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start",
      paddingBottom: 16, marginBottom: 16,
      borderBottomWidth: 2, borderBottomColor: accent,
    },
    logo: { width: 110, height: 46, objectFit: "contain", marginBottom: 6 },
    orgName: { fontSize: 16, ...invoicePdfBold(), color: accent, marginBottom: 3 },
    orgDetail: { fontSize: 8, color: "#64748b", marginBottom: 2 },
    headerRight: { alignItems: "flex-end" },
    invoiceWord: { fontSize: 9, color: "#94a3b8", textTransform: "uppercase", letterSpacing: 2, marginBottom: 4 },
    invoiceNum: { fontSize: 22, ...invoicePdfBold(), color: accent },
    badge: { marginTop: 8, fontSize: 8, paddingHorizontal: 7, paddingVertical: 3, borderRadius: 3, alignSelf: "flex-end" },

    // Divider
    divider: { height: 1, backgroundColor: "#e2e8f0", marginBottom: 24 },

    // Meta row
    metaRow: { flexDirection: "row", marginBottom: 32 },
    metaBlock: { flex: 1, paddingRight: 20 },
    metaLabel: { fontSize: 7, color: "#94a3b8", textTransform: "uppercase", letterSpacing: 1.5, ...invoicePdfBold(), marginBottom: 6 },
    metaValue: { fontSize: 10, color: "#0f172a", marginBottom: 2 },
    metaSub: { fontSize: 8, color: "#64748b", marginBottom: 1 },

    // Table
    table: { marginBottom: 20 },
    tableHeader: {
      flexDirection: "row", paddingVertical: 7, paddingHorizontal: 10,
      borderTopWidth: 1, borderTopColor: accent,
      borderBottomWidth: 1, borderBottomColor: accent,
    },
    tableRow: { flexDirection: "row", paddingVertical: 8, paddingHorizontal: 10, borderBottomWidth: 1, borderBottomColor: "#f1f5f9" },
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
    headerText: { fontSize: 9, ...invoicePdfBold(), color: "#374151", textTransform: "uppercase" },

    // Totals
    totalsOuter: { flexDirection: "row", justifyContent: "flex-end" },
    totalsBox: { width: 240 },
    totalRow: { flexDirection: "row", paddingVertical: 5, borderBottomWidth: 1, borderBottomColor: "#f1f5f9" },
    totalLabel: { flex: 1, color: "#64748b" },
    totalValue: { width: 90, textAlign: "right", color: "#1e293b" },
    grandRow: {
      flexDirection: "row", paddingVertical: 8, marginTop: 2,
      borderTopWidth: 2, borderTopColor: accent,
    },
    grandLabel: { flex: 1, ...invoicePdfBold(), fontSize: 12, color: accent },
    grandValue: { width: 90, textAlign: "right", ...invoicePdfBold(), fontSize: 12, color: accent },

    // Notes
    notesSection: { marginTop: 32, paddingTop: 16, borderTopWidth: 1, borderTopColor: "#e2e8f0" },
    notesLabel: { fontSize: 7, color: "#94a3b8", textTransform: "uppercase", letterSpacing: 1.5, ...invoicePdfBold(), marginBottom: 6 },
    notesText: { fontSize: 9, color: "#475569", lineHeight: 1.6 },

    // Footer
    footer: { position: "absolute", bottom: 14, left: 28, right: 28, flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
    footerLine: { position: "absolute", bottom: 28, left: 28, right: 28, height: 1, backgroundColor: "#f1f5f9" },
    footerText: { fontSize: 8, color: "#cbd5e1" },
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
            <Text style={styles.invoiceWord}>Invoice</Text>
            <Text style={styles.invoiceNum}>{invoice.number}</Text>
            {invoice.showStatus !== false && (
              <View style={[styles.badge, { backgroundColor: STATUS_COLORS[invoice.status] ?? "#e5e7eb" }]}>
                <Text style={{ fontSize: 8, color: "#374151" }}>{displayStatus}</Text>
              </View>
            )}
          </View>
        </View>

        {/* === Bill-to + Dates === */}
        <View style={styles.metaRow}>
          <View style={[styles.metaBlock, { flex: 2 }]}>
            <Text style={[styles.metaValue, { marginBottom: 4 }]}>
              <Text>To: </Text>
              <Text style={invoicePdfBold()}>{customer.name}</Text>
            </Text>
            {customer.vat && <Text style={styles.metaSub}>VAT / Tax ID: {customer.vat}</Text>}
            {customerAddressLines.map((line, i) => (
              <Text key={`ca-${i}`} style={styles.metaSub}>{line}</Text>
            ))}
            {customer.phone && <Text style={styles.metaSub}>Phone: {customer.phone}</Text>}
            {customer.email && <Text style={styles.metaSub}>Email: {customer.email}</Text>}
          </View>
          <View style={styles.metaBlock}>
            <Text style={styles.metaLabel}>Issue Date</Text>
            <Text style={styles.metaValue}>{formatDate(invoice.issuedAt)}</Text>
            <Text style={[styles.metaLabel, { marginTop: 8 }]}>Due Date</Text>
            <Text style={styles.metaValue}>{formatDate(invoice.dueDate)}</Text>
          </View>
          {(invoice.periodFrom || invoice.periodTo) && (
            <View style={[styles.metaBlock, { paddingRight: 0 }]}>
              <Text style={styles.metaLabel}>Invoiced Period</Text>
              <Text style={styles.metaValue}>
                {formatDate(invoice.periodFrom)} – {formatDate(invoice.periodTo)}
              </Text>
            </View>
          )}
        </View>

        {/* === Line items === */}
        <View style={styles.table}>
          <View style={styles.tableHeader}>
            <Text style={[styles.headerText, styles.colDesc]}>Item / Service</Text>
            <Text style={[styles.headerText, styles.colQty]}>Qty</Text>
            <Text style={[styles.headerText, styles.colPrice]}>Unit Price</Text>
            <Text style={[styles.headerText, styles.colTotal]}>Total</Text>
          </View>
          {lines.map((line, i) => (
            <View key={i} style={styles.tableRow}>
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
        </View>

        {/* === Totals === */}
        <View style={styles.totalsOuter}>
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
                {parseFloat(invoice.amountPaid) > 0 ? "Balance Due" : "Total Due"}
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

        {/* === T&C === */}
        {invoice.termsAndConditions && (
          <View style={[styles.notesSection, { marginTop: 16 }]}>
            <Text style={styles.notesLabel}>Terms &amp; Conditions</Text>
            <Text style={styles.notesText}>{invoice.termsAndConditions}</Text>
          </View>
        )}

        {/* === Notes === */}
        {(invoice.notes || data.footerText) && (
          <View style={styles.notesSection}>
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

        {/* === Footer === */}
        <View style={styles.footerLine} />
        <View style={styles.footer}>
          <Text style={styles.footerText}>{org.name}</Text>
          <Text style={styles.footerText}>Generated {new Date().toLocaleDateString("en-GB")}</Text>
        </View>
      </Page>
    </Document>
  );
}
