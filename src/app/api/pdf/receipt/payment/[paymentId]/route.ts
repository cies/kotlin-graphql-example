import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { generateReceiptPdf } from "@/lib/pdf/receipt-pdf";
import { getPaymentWithReceiptContext, toReceiptPdfData } from "@/lib/receipt/resolve-receipt-pdf-data";

export const runtime = "nodejs";

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ paymentId: string }> }
) {
  const { paymentId } = await params;
  const isInline = req.nextUrl.searchParams.get("preview") === "1";

  const session = await auth();
  if (!session?.user) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const payment = await getPaymentWithReceiptContext(paymentId);

  if (!payment) {
    return NextResponse.json({ error: "Receipt not found" }, { status: 404 });
  }

  const invoice = payment.invoice;

  if (session.user.userType === "STAFF") {
    if (session.user.organizationId !== invoice.organizationId) {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }
  } else if (session.user.userType === "CUSTOMER_CONTACT") {
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
  } else {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const data = toReceiptPdfData(payment);

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
