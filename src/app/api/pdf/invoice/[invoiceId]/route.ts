import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { buildInvoicePdfData } from "@/lib/actions/invoices";
import { generateInvoicePdf } from "@/lib/pdf/invoice-pdf";
import { prisma } from "@/lib/db/prisma";

export const runtime = "nodejs";

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ invoiceId: string }> }
) {
  const { invoiceId } = await params;
  const tokenParam = req.nextUrl.searchParams.get("token");
  const isInline = req.nextUrl.searchParams.get("preview") === "1";

  // Token-based public access (from /invoice/[token] page)
  if (tokenParam) {
    const invoice = await prisma.invoice.findUnique({
      where: { id: invoiceId },
      select: { viewToken: true, status: true, number: true, organizationId: true },
    });
    if (!invoice || invoice.viewToken !== tokenParam || invoice.status === "DRAFT") {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }
    // Fall through to PDF generation
  } else {
    const session = await auth();
    if (!session?.user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const invoice = await prisma.invoice.findUnique({
      where: { id: invoiceId },
      select: { organizationId: true, number: true },
    });

    if (!invoice) {
      return NextResponse.json({ error: "Invoice not found" }, { status: 404 });
    }

    if (
      session.user.userType === "STAFF" &&
      session.user.organizationId !== invoice.organizationId
    ) {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }

    if (session.user.userType === "CUSTOMER_CONTACT") {
      const contact = await prisma.customerContact.findFirst({
        where: {
          organizationId: invoice.organizationId,
          userId: session.user.id,
          canSeeInvoices: true,
        },
      });
      if (!contact) {
        return NextResponse.json({ error: "Forbidden" }, { status: 403 });
      }
    }
  }

  const invoiceForNumber = await prisma.invoice.findUnique({
    where: { id: invoiceId },
    select: { number: true },
  });

  try {
    const data = await buildInvoicePdfData(invoiceId);
    if (!data) {
      return NextResponse.json({ error: "Invoice not found" }, { status: 404 });
    }

    const pdfBuffer = await generateInvoicePdf(data);
    const filename = invoiceForNumber?.number ?? data.invoice.number;

    const disposition = isInline
      ? `inline; filename="${filename}.pdf"`
      : `attachment; filename="${filename}.pdf"`;

    return new NextResponse(pdfBuffer as unknown as BodyInit, {
      status: 200,
      headers: {
        "Content-Type": "application/pdf",
        "Content-Disposition": disposition,
        "Content-Length": String(pdfBuffer.length),
      },
    });
  } catch (err) {
    console.error("[PDF] Generation failed:", err);
    return NextResponse.json({ error: "Failed to generate PDF" }, { status: 500 });
  }
}
