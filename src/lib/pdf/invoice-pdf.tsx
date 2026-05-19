import React from "react";
import { renderToBuffer } from "@react-pdf/renderer";
import type { InvoiceTemplate } from "@prisma/client";
import { prepareInvoicePdfFonts } from "./invoice-font";
import type { InvoicePdfData } from "./invoice-templates/types";
import { ClassicInvoiceDocument } from "./invoice-templates/classic";
import { ModernInvoiceDocument } from "./invoice-templates/modern";
import { MinimalInvoiceDocument } from "./invoice-templates/minimal";
import { CorporateInvoiceDocument } from "./invoice-templates/corporate";
import { fetchAndConvertLogo } from "@/lib/branding/logo-png";

export type { InvoicePdfData };

function pickTemplate(template: InvoiceTemplate | null | undefined, data: InvoicePdfData) {
  switch (template) {
    case "MODERN":
      return <ModernInvoiceDocument data={data} />;
    case "MINIMAL":
      return <MinimalInvoiceDocument data={data} />;
    case "CORPORATE":
      return <CorporateInvoiceDocument data={data} />;
    case "CLASSIC":
    default:
      return <ClassicInvoiceDocument data={data} />;
  }
}

export async function generateInvoicePdf(data: InvoicePdfData): Promise<Buffer> {
  prepareInvoicePdfFonts(data.invoicePdfFont ?? "HELVETICA");

  // Fetch and convert logo to PNG base64 if available
  let logoPngBase64: string | undefined;
  if (data.org.logoUrl) {
    try {
      const pngBuf = await fetchAndConvertLogo(data.org.logoUrl);
      logoPngBase64 = pngBuf.toString("base64");
    } catch {
      // Logo unavailable - templates fall back to text
    }
  }

  const dataWithLogo: InvoicePdfData = { ...data, logoPngBase64 };
  const template = data.invoice.template ?? "CLASSIC";
  const element = pickTemplate(template, dataWithLogo);
  const buffer = await renderToBuffer(element);
  return Buffer.from(buffer);
}

// Keep a legacy named export for backwards compat with worker imports
export { ClassicInvoiceDocument as InvoicePdfDocument };
