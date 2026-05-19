import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import {
  buildReceiptPdfDataFromPayment,
  generateReceiptPdf,
} from "@/lib/pdf/receipt-pdf";

export const runtime = "nodejs";

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ invoiceId: string }> }
) {
  const { invoiceId } = await params;
  const paymentId = req.nextUrl.searchParams.get("paymentId");
  const isInline = req.nextUrl.searchParams.get("preview") === "1";

  const session = await auth();
  if (!session?.user) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId },
    include: {
      customer: true,
      lines: { orderBy: { sortOrder: "asc" } },
      organization: { include: { settings: true } },
      payments: paymentId
        ? { where: { id: paymentId } }
        : { orderBy: { paidAt: "desc" }, take: 1 },
    },
  });

  if (!invoice || !invoice.payments[0]) {
    return NextResponse.json({ error: "Receipt not found" }, { status: 404 });
  }

  if (session.user.userType === "STAFF" && session.user.organizationId !== invoice.organizationId) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }
  if (session.user.userType === "CUSTOMER_CONTACT") {
    const contact = await prisma.customerContact.findFirst({
      where: {
        organizationId: invoice.organizationId,
        customerId: invoice.customerId,
        userId: session.user.id,
        canSeeInvoices: true,
      },
      select: { id: true },
    });
    if (!contact) {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }
  }

  const payment = invoice.payments[0];
  const org = invoice.organization;

  const data = buildReceiptPdfDataFromPayment({
    payment,
    invoice: {
      number: invoice.number,
      currency: invoice.currency,
      subtotal: invoice.subtotal,
      vat: invoice.vat,
      total: invoice.total,
      periodFrom: invoice.periodFrom,
      periodTo: invoice.periodTo,
      customer: invoice.customer,
      lines: invoice.lines,
    },
    org: { name: org.name, settings: org.settings },
  });

  const pdfBuffer = await generateReceiptPdf(data);
  const rcptFilePart = payment.receiptNumber ?? payment.id.slice(-8);
  const dispositionType = isInline ? "inline" : "attachment";

  return new NextResponse(pdfBuffer as unknown as BodyInit, {
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `${dispositionType}; filename="receipt-${invoice.number}-${rcptFilePart}.pdf"`,
    },
  });
}
