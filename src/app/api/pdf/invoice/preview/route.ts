import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { generateInvoicePdf } from "@/lib/pdf/invoice-pdf";
import type { InvoicePdfData } from "@/lib/pdf/invoice-templates/types";
import { buildCompanyAddress, formatBillingAddress, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { z } from "zod";
import type { Currency, InvoiceTemplate } from "@prisma/client";

export const runtime = "nodejs";

const lineSchema = z.object({
  name: z.string().optional(),
  description: z.string().optional(),
  quantity: z.string(),
  qtyType: z.enum(["QTY", "HOURS", "QTY_HOURS"]).optional(),
  unitPrice: z.string(),
});

const previewSchema = z.object({
  orgSlug: z.string().min(1),
  customerId: z.string().optional(),
  currency: z.string().min(1),
  vatRate: z.string().optional(),
  vatIncluded: z.boolean().optional(),
  periodFrom: z.string().optional(),
  periodTo: z.string().optional(),
  dueDate: z.string().optional(),
  issuedAt: z.string().optional(),
  notes: z.string().optional(),
  termsAndConditions: z.string().optional(),
  template: z.string().optional(),
  discountType: z.enum(["NONE", "PERCENTAGE", "FIXED"]).optional(),
  discountValue: z.string().optional(),
  discountBeforeTax: z.boolean().optional(),
  lines: z.array(lineSchema).optional(),
});

export async function POST(req: NextRequest) {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON" }, { status: 400 });
  }

  const parsed = previewSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ error: "Invalid payload" }, { status: 400 });
  }

  const {
    orgSlug, customerId, currency, vatRate, vatIncluded,
    periodFrom, periodTo, dueDate, issuedAt, notes, termsAndConditions, template,
    discountType, discountValue, discountBeforeTax, lines,
  } = parsed.data;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) return NextResponse.json({ error: "Organization not found" }, { status: 404 });

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
  });
  if (!member) return NextResponse.json({ error: "Forbidden" }, { status: 403 });

  // Customer is optional - use placeholder when not set
  let customerName = "Preview Customer";
  let customerEmail: string | null = null;
  let customerVat: string | null = null;
  let customerAddress: string | undefined;
  let customerPhone: string | null = null;

  if (customerId) {
    const customer = await prisma.customer.findUnique({
      where: { id: customerId, organizationId: org.id },
    });
    if (customer) {
      const individualName = [customer.firstName, customer.lastName]
        .filter(Boolean)
        .join(" ");
      customerName = customer.companyName || individualName || customer.email || "Customer";
      customerEmail = customer.hideEmailOnInvoice ? null : customer.email;
      customerVat = customer.vat;
      customerAddress = formatBillingAddress(customer.billingAddress);
      customerPhone = customer.hidePhoneOnInvoice ? null : customer.phone;
    }
  }

  const settings = org.settings;
  const vatDecimal = parseFloat(vatRate ?? settings?.vatRate?.toString() ?? "0");
  const resolvedVatIncluded = vatIncluded ?? settings?.defaultVatIncluded ?? false;
  const resolvedDiscountType = discountType ?? "NONE";
  const resolvedDiscountValue = parseFloat(discountValue ?? "0") || 0;
  const resolvedDiscountBefore = discountBeforeTax !== false;

  const processedLines = (lines ?? []).map((l) => ({
    name: l.name || "",
    description: l.description || "",
    quantity: l.quantity || "0",
    qtyType: l.qtyType,
    unitPrice: l.unitPrice || "0",
    lineTotal: parseFloat(l.quantity || "0") * parseFloat(l.unitPrice || "0"),
  }));

  const lineSum = processedLines.reduce((sum, l) => sum + l.lineTotal, 0);

  let discountAmount = 0;
  if (resolvedDiscountBefore) {
    if (resolvedDiscountType === "PERCENTAGE") discountAmount = lineSum * (resolvedDiscountValue / 100);
    else if (resolvedDiscountType === "FIXED") discountAmount = Math.min(resolvedDiscountValue, lineSum);
  }

  const taxable = resolvedDiscountBefore ? lineSum - discountAmount : lineSum;
  let subtotal: number, vatAmount: number, total: number;
  if (resolvedVatIncluded && vatDecimal > 0) {
    vatAmount = taxable * vatDecimal / (1 + vatDecimal);
    subtotal = lineSum;
    total = taxable;
  } else {
    subtotal = lineSum;
    vatAmount = taxable * vatDecimal;
    total = taxable + vatAmount;
  }
  if (!resolvedDiscountBefore && resolvedDiscountType !== "NONE") {
    if (resolvedDiscountType === "PERCENTAGE") discountAmount = total * (resolvedDiscountValue / 100);
    else discountAmount = Math.min(resolvedDiscountValue, total);
    total -= discountAmount;
  }

  const resolvedTemplate = (template ?? settings?.defaultInvoiceTemplate ?? "CLASSIC") as InvoiceTemplate;

  const pdfData: InvoicePdfData = {
    org: {
      name: settings?.companyName ?? org.name,
      address: buildCompanyAddress(settings ?? {}),
      vat: settings?.companyVat,
      logoUrl: settings?.companyLogoUrl ?? undefined,
      billingEmail: settings?.smtpFrom?.trim() || null,
      phoneContact: settings?.companyPhone?.trim() || null,
    },
    invoice: {
      number: "PREVIEW",
      status: "DRAFT",
      showStatus: (settings as unknown as { showInvoiceStatus?: boolean } | null)?.showInvoiceStatus ?? true,
      currency: currency as Currency,
      issuedAt: issuedAt ? new Date(issuedAt) : new Date(),
      dueDate: dueDate ? new Date(dueDate) : null,
      subtotal: subtotal.toFixed(2),
      vat: vatAmount.toFixed(2),
      vatRate: vatDecimal,
      total: total.toFixed(2),
      amountPaid: "0.00",
      discount: discountAmount.toFixed(2),
      discountType: resolvedDiscountType,
      discountBeforeTax: resolvedDiscountBefore,
      notes,
      termsAndConditions,
      template: resolvedTemplate,
      vatIncluded: resolvedVatIncluded,
      periodFrom: periodFrom ? new Date(periodFrom) : undefined,
      periodTo: periodTo ? new Date(periodTo) : undefined,
    },
    customer: {
      name: customerName,
      email: customerEmail,
      vat: customerVat,
      address: customerAddress,
      phone: customerPhone,
    },
    lines: processedLines.length > 0 ? processedLines.map((l) => ({
      name: l.name,
      description: l.description || undefined,
      quantity: l.quantity,
      qtyType: l.qtyType,
      unitPrice: l.unitPrice,
      total: l.lineTotal.toFixed(2),
    })) : [{
      name: "Sample Line Item",
      description: undefined,
      quantity: "1",
      qtyType: undefined,
      unitPrice: "0.00",
      total: "0.00",
    }],
    accentColor: settings?.invoiceAccentColor ?? undefined,
    footerText: settings?.invoiceFooterText ?? undefined,
    numberFormatStyle: normalizeNumberFormatStyle(
      (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
    ),
    invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
  };

  try {
    const pdfBuffer = await generateInvoicePdf(pdfData);
    return new NextResponse(pdfBuffer as unknown as BodyInit, {
      status: 200,
      headers: {
        "Content-Type": "application/pdf",
        "Content-Disposition": 'inline; filename="invoice-preview.pdf"',
        "Content-Length": String(pdfBuffer.length),
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    console.error("[PDF Preview] Generation failed:", err);
    return NextResponse.json({ error: "Failed to generate preview" }, { status: 500 });
  }
}
