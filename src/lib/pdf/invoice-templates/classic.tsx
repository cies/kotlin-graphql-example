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

export function ClassicInvoiceDocument({ data }: { data: InvoicePdfData }) {
  const accent = data.accentColor ?? "#2563eb";
  const { org, invoice, customer, lines } = data;
  const amountDue = (parseFloat(invoice.total) - parseFloat(invoice.amountPaid)).toFixed(2);
  const displayStatus = getInvoiceDisplayStatus(invoice.status, invoice.displayStatus);

  const styles = StyleSheet.create({
    page: { padding: 24, fontFamily: invoicePdfFont(), fontSize: 10, color: "#1a1a1a" },
    // Fixed repeating header (all pages)
    pageHeader: {
      flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start",
      marginBottom: 20, paddingBottom: 16,
      borderBottomWidth: 1, borderBottomColor: "#e5e7eb",
    },
    orgBlock: { maxWidth: 220 },
    logo: { width: 120, height: 50, objectFit: "contain", marginBottom: 8 },
    orgName: { fontSize: 16, ...invoicePdfBold(), marginBottom: 4 },
    orgDetail: { fontSize: 9, color: "#555", marginBottom: 2 },
    invoiceLabel: { fontSize: 24, ...invoicePdfBold(), color: accent },
    invoiceNumber: { fontSize: 12, color: "#555", marginTop: 4, textAlign: "right" },
    badge: { fontSize: 9, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4, alignSelf: "flex-end", marginTop: 8 },
    metaRow: { flexDirection: "row", gap: 40, marginBottom: 24 },
    metaBlock: { flex: 1 },
    metaTitle: { fontSize: 9, color: "#777", marginBottom: 4, textTransform: "uppercase" },
    metaValue: { fontSize: 10 },
    table: { marginTop: 16, borderTopWidth: 1, borderTopColor: "#e5e7eb" },
    tableHeader: { flexDirection: "row", backgroundColor: "#f9fafb", paddingVertical: 6, paddingHorizontal: 8, borderBottomWidth: 1, borderBottomColor: "#e5e7eb" },
    tableRow: { flexDirection: "row", paddingVertical: 6, paddingHorizontal: 8, borderBottomWidth: 1, borderBottomColor: "#f3f4f6" },
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
    headerText: { fontSize: 9, ...invoicePdfBold(), color: "#374151" },
    totals: { alignItems: "flex-end", marginTop: 16 },
    totalRow: { flexDirection: "row", marginBottom: 4, width: 220 },
    totalLabel: { flex: 1, color: "#555" },
    totalValue: { width: 80, textAlign: "right" },
    grandTotal: { ...invoicePdfBold(), fontSize: 12 },
    notes: { marginTop: 32, paddingTop: 16, borderTopWidth: 1, borderTopColor: "#e5e7eb" },
    notesTitle: { fontSize: 9, color: "#777", marginBottom: 4, textTransform: "uppercase" },
    notesText: { fontSize: 9, color: "#555" },
    footer: { position: "absolute", bottom: 18, left: 24, right: 24, textAlign: "center", fontSize: 8, color: "#aaa" },
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
          <View style={styles.orgBlock}>
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
          <View style={{ alignItems: "flex-end" }}>
            <Text style={styles.invoiceLabel}>INVOICE</Text>
            <Text style={styles.invoiceNumber}>{invoice.number}</Text>
            {invoice.showStatus !== false && (
              <View style={[styles.badge, { backgroundColor: STATUS_COLORS[invoice.status] ?? "#e5e7eb" }]}>
                <Text>{displayStatus}</Text>
              </View>
            )}
          </View>
        </View>

        {/* === Meta: Bill-To + dates (page 1 only, naturally) === */}
        <View style={styles.metaRow}>
          <View style={styles.metaBlock}>
            <Text style={styles.metaValue}>
              <Text>To: </Text>
              <Text style={invoicePdfBold()}>{customer.name}</Text>
            </Text>
            {customer.vat && <Text style={[styles.metaValue, { color: "#555" }]}>VAT / Tax ID: {customer.vat}</Text>}
            {customerAddressLines.map((line, i) => (
              <Text key={`ca-${i}`} style={[styles.metaValue, { color: "#555" }]}>{line}</Text>
            ))}
            {customer.phone && <Text style={[styles.metaValue, { color: "#555" }]}>Phone: {customer.phone}</Text>}
            {customer.email && <Text style={[styles.metaValue, { color: "#555" }]}>Email: {customer.email}</Text>}
          </View>
          <View style={styles.metaBlock}>
            <Text style={styles.metaTitle}>Issue Date</Text>
            <Text style={styles.metaValue}>{formatDate(invoice.issuedAt)}</Text>
            <Text style={[styles.metaTitle, { marginTop: 8 }]}>Due Date</Text>
            <Text style={styles.metaValue}>{formatDate(invoice.dueDate)}</Text>
          </View>
          {(invoice.periodFrom || invoice.periodTo) && (
            <View style={styles.metaBlock}>
              <Text style={styles.metaTitle}>Invoiced Period</Text>
              <Text style={styles.metaValue}>
                {formatDate(invoice.periodFrom)} – {formatDate(invoice.periodTo)}
              </Text>
            </View>
          )}
        </View>

        {/* === Line items table === */}
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
        <View style={styles.totals}>
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
          <View style={[styles.totalRow, { borderTopWidth: 1, borderTopColor: "#e5e7eb", paddingTop: 4 }]}>
            <Text style={[styles.totalLabel, styles.grandTotal]}>Total</Text>
            <Text style={[styles.totalValue, styles.grandTotal]}>
              {formatCurrencyWithStyleAndCode(invoice.total, invoice.currency, data.numberFormatStyle)}
            </Text>
          </View>
          {parseFloat(invoice.amountPaid) > 0 && (
            <>
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, invoicePdfLineItemNameStyle()]}>Amount Paid</Text>
                <Text style={[styles.totalValue, invoicePdfLineItemNameStyle()]}>
                  {formatCurrencyWithStyle(invoice.amountPaid, invoice.currency, data.numberFormatStyle)}
                </Text>
              </View>
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, styles.grandTotal]}>Amount Due</Text>
                <Text style={[styles.totalValue, styles.grandTotal]}>
                  {formatCurrencyWithStyleAndCode(amountDue, invoice.currency, data.numberFormatStyle)}
                </Text>
              </View>
            </>
          )}
        </View>

        {invoice.notes && (
          <View style={styles.notes}>
            <Text style={styles.notesTitle}>Notes</Text>
            <Text style={styles.notesText}>{invoice.notes}</Text>
          </View>
        )}

        {invoice.termsAndConditions && (
          <View style={[styles.notes, { marginTop: 16 }]}>
            <Text style={styles.notesTitle}>Terms &amp; Conditions</Text>
            <Text style={styles.notesText}>{invoice.termsAndConditions}</Text>
          </View>
        )}

        {data.footerText && (
          <View style={[styles.notes, { marginTop: 16 }]}>
            <Text style={styles.notesText}>{data.footerText}</Text>
          </View>
        )}

        <Text style={styles.footer}>
          {org.name} - Generated {new Date().toLocaleDateString("en-GB")}
        </Text>
      </Page>
    </Document>
  );
}
