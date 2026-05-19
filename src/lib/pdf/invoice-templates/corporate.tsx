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
import { resolveCorporateChrome } from "./corporate-tones";

export function CorporateInvoiceDocument({ data }: { data: InvoicePdfData }) {
  const chrome = resolveCorporateChrome(data.accentColor);
  const { org, invoice, customer, lines } = data;
  const amountDue = (parseFloat(invoice.total) - parseFloat(invoice.amountPaid)).toFixed(2);
  const displayStatus = getInvoiceDisplayStatus(invoice.status, invoice.displayStatus);

  const styles = StyleSheet.create({
    page: { padding: 20, paddingBottom: 38, fontFamily: invoicePdfFont(), fontSize: 10, color: "#1a1a1a", backgroundColor: "#ffffff" },

    pageHeader: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 8 },
    logo: { width: 168, height: 68, objectFit: "contain" },
    invoiceBlock: { alignItems: "flex-end" },
    invoiceWord: { fontSize: 26, ...invoicePdfBold(), color: chrome.headerCellText },
    invoiceNum: { fontSize: 10, ...invoicePdfBold(), color: chrome.headerCellText, marginTop: 3 },
    statusBadge: { marginTop: 6, alignSelf: "flex-end", fontSize: 8, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 3 },

    fromTo: { flexDirection: "row", marginBottom: 18 },
    fromToCol: { flex: 1 },
    fromToColRight: { flex: 1, paddingLeft: 24, borderLeftWidth: 1, borderLeftColor: "#e5e7eb" },
    ftHeading: {
      fontSize: 11,
      ...invoicePdfBold(),
      color: chrome.headerCellText,
      marginBottom: 3,
    },
    ftLine: { fontSize: 9, color: "#4b5563", marginBottom: 2 },

    datesRow: {
      flexDirection: "row",
      borderWidth: 1,
      borderColor: chrome.datesBorder,
      borderRadius: 3,
      backgroundColor: chrome.datesBg,
      paddingHorizontal: 14,
      paddingVertical: 9,
      marginBottom: 20,
    },
    dateBlock: { flex: 1 },
    dateSep: { width: 1, backgroundColor: chrome.datesSep, marginHorizontal: 14 },
    dateLabel: { fontSize: 7, color: "#6b7280", textTransform: "uppercase", marginBottom: 3 },
    dateValue: { fontSize: 10, color: "#111827", ...invoicePdfBold() },

    tableHeaderRow: {
      flexDirection: "row",
      paddingVertical: 7,
      paddingHorizontal: 10,
      backgroundColor: chrome.tableHeaderBg,
    },
    tableRow: {
      flexDirection: "row",
      paddingVertical: 7,
      paddingHorizontal: 10,
      borderBottomWidth: 1,
      borderBottomColor: chrome.tableRowBorder,
    },
    tableRowAlt: { backgroundColor: chrome.tableRowAltBg },
    lineItemName: {
      fontSize: INVOICE_LINE_ITEM_NAME_SIZE,
      color: INVOICE_LINE_ITEM_QTY_PRICE_COLOR,
      ...invoicePdfLineItemNameStyle(),
    },
    lineItemDesc: { fontSize: 8, color: INVOICE_LINE_ITEM_DESC_COLOR, marginTop: 2 },
    colDesc: { flex: 3 },
    colQty: { flex: 1, textAlign: "right" },
    colPrice: { flex: 1, textAlign: "right" },
    colTotal: { flex: 1, textAlign: "right" },
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
    headerCell: { fontSize: 9, ...invoicePdfBold(), color: chrome.headerCellText },

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
    totalLabel: { flex: 1, color: "#6b7280" },
    totalValue: { width: 90, textAlign: "right", color: "#1a1a1a" },
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
    },
    grandLabel: { flex: 1, ...invoicePdfBold(), fontSize: 11, color: "#ffffff" },
    grandValue: { width: 90, textAlign: "right", ...invoicePdfBold(), fontSize: 11, color: "#ffffff" },

    notes: { marginTop: 24, paddingTop: 12, borderTopWidth: 1, borderTopColor: "#e5e7eb" },
    notesLabel: {
      fontSize: 7,
      color: "#111827",
      textTransform: "uppercase",
      letterSpacing: 0.5,
      ...invoicePdfBold(),
      marginBottom: 5,
    },
    notesText: { fontSize: 9, color: "#4b5563", lineHeight: 1.5 },

    footer: {
      position: "absolute",
      bottom: 0,
      left: 0,
      right: 0,
      height: 26,
      backgroundColor: chrome.footerBg,
      flexDirection: "row",
      justifyContent: "space-between",
      alignItems: "center",
      paddingHorizontal: 20,
    },
    footerText: { fontSize: 8, color: "rgba(255,255,255,0.65)" },
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
        <View fixed style={styles.pageHeader}>
          <View>
            {data.logoPngBase64 ? (
              <Image src={`data:image/png;base64,${data.logoPngBase64}`} style={styles.logo} />
            ) : null}
          </View>
          <View style={styles.invoiceBlock}>
            <Text style={styles.invoiceWord}>INVOICE</Text>
            <Text style={styles.invoiceNum}>{invoice.number}</Text>
            {invoice.showStatus !== false && (
              <View style={[styles.statusBadge, { backgroundColor: STATUS_COLORS[invoice.status] ?? "#e5e7eb" }]}>
                <Text style={{ fontSize: 8, color: "#1f2937" }}>{displayStatus}</Text>
              </View>
            )}
          </View>
        </View>

        <View style={styles.fromTo}>
          <View style={styles.fromToCol}>
            <Text style={styles.ftHeading}>From: {org.name}</Text>
            {org.vat ? <Text style={styles.ftLine}>VAT ID: {org.vat}</Text> : null}
            {orgAddressLines.map((line, i) => (
              <Text key={`oa-${i}`} style={styles.ftLine}>
                {line}
              </Text>
            ))}
            {orgContactLines.map((line, i) => (
              <Text key={`oc-${i}`} style={styles.ftLine}>
                {line}
              </Text>
            ))}
            {org.billingEmail ? <Text style={styles.ftLine}>{org.billingEmail}</Text> : null}
          </View>
          <View style={styles.fromToColRight}>
            <Text style={styles.ftHeading}>To: {customer.name}</Text>
            {customer.vat ? <Text style={styles.ftLine}>VAT / Tax ID: {customer.vat}</Text> : null}
            {customerAddressLines.map((line, i) => (
              <Text key={`ca-${i}`} style={styles.ftLine}>
                {line}
              </Text>
            ))}
            {customer.phone ? <Text style={styles.ftLine}>Phone: {customer.phone}</Text> : null}
            {customer.email ? <Text style={styles.ftLine}>Email: {customer.email}</Text> : null}
          </View>
        </View>

        <View style={styles.datesRow}>
          <View style={styles.dateBlock}>
            <Text style={styles.dateLabel}>Issue Date</Text>
            <Text style={styles.dateValue}>{formatDate(invoice.issuedAt)}</Text>
          </View>
          <View style={styles.dateSep} />
          <View style={styles.dateBlock}>
            <Text style={styles.dateLabel}>Due Date</Text>
            <Text style={styles.dateValue}>{formatDate(invoice.dueDate)}</Text>
          </View>
          <View style={styles.dateSep} />
          <View style={styles.dateBlock}>
            <Text style={styles.dateLabel}>Currency</Text>
            <Text style={styles.dateValue}>{invoice.currency}</Text>
          </View>
          {(invoice.periodFrom || invoice.periodTo) && (
            <>
              <View style={styles.dateSep} />
              <View style={[styles.dateBlock, { flex: 2 }]}>
                <Text style={styles.dateLabel}>Invoiced Period</Text>
                <Text style={styles.dateValue}>
                  {formatDate(invoice.periodFrom)} – {formatDate(invoice.periodTo)}
                </Text>
              </View>
            </>
          )}
        </View>

        <View style={styles.tableHeaderRow}>
          <Text style={[styles.headerCell, styles.colDesc]}>Item / Service</Text>
          <Text style={[styles.headerCell, styles.colQty]}>Qty</Text>
          <Text style={[styles.headerCell, styles.colPrice]}>Unit Price</Text>
          <Text style={[styles.headerCell, styles.colTotal]}>Total</Text>
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

        <View style={styles.totalsWrap}>
          <View style={styles.totalsColumn}>
            <View style={styles.totalsBoxUpper}>
              <View style={styles.totalRow}>
                <Text style={[styles.totalLabel, invoicePdfLineItemNameStyle()]}>Subtotal</Text>
                <Text style={[styles.totalValue, invoicePdfLineItemNameStyle()]}>
                  {formatCurrencyWithStyle(invoice.subtotal, invoice.currency, data.numberFormatStyle)}
                </Text>
              </View>
              {hasDiscount && invoice.discountBeforeTax && (
                <View style={styles.totalRow}>
                  <Text style={[styles.totalLabel, { color: "#16a34a" }]}>{discountLabel}</Text>
                  <Text style={[styles.totalValue, { color: "#16a34a" }]}>
                    -{formatCurrencyWithStyle(invoice.discount ?? "0", invoice.currency, data.numberFormatStyle)}
                  </Text>
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
                  <Text style={[styles.totalValue, { color: "#16a34a" }]}>
                    -{formatCurrencyWithStyle(invoice.discount ?? "0", invoice.currency, data.numberFormatStyle)}
                  </Text>
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
            </View>
            <View style={styles.grandRow}>
              <Text style={styles.grandLabel}>{parseFloat(invoice.amountPaid) > 0 ? "Amount Due" : "Total Due"}</Text>
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

        {invoice.termsAndConditions && (
          <View style={styles.notes}>
            <Text style={styles.notesLabel}>Terms &amp; Conditions</Text>
            <Text style={styles.notesText}>{invoice.termsAndConditions}</Text>
          </View>
        )}

        {(invoice.notes || data.footerText) && (
          <View style={styles.notes}>
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

        <View style={styles.footer}>
          <Text style={styles.footerText}>{org.name}</Text>
          <Text style={styles.footerText}>Generated {new Date().toLocaleDateString("en-GB")}</Text>
        </View>
      </Page>
    </Document>
  );
}
