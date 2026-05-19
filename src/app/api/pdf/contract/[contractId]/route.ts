import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { buildContractPdfData } from "@/lib/actions/contracts";
import { generateContractPdf } from "@/lib/pdf/contract-pdf";

export const runtime = "nodejs";

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ contractId: string }> }
) {
  const { contractId } = await params;
  const session = await auth();
  if (!session?.user) return NextResponse.json({ error: "Unauthorized" }, { status: 401 });

  const contract = await prisma.contract.findUnique({
    where: { id: contractId },
    select: { id: true, title: true, organizationId: true, customerId: true },
  });
  if (!contract) return NextResponse.json({ error: "Contract not found" }, { status: 404 });

  if (session.user.userType === "STAFF" && session.user.organizationId !== contract.organizationId) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  if (session.user.userType === "CUSTOMER_CONTACT") {
    const contact = await prisma.customerContact.findFirst({
      where: {
        userId: session.user.id,
        organizationId: contract.organizationId,
        customerId: contract.customerId,
        canSeeContracts: true,
      },
      select: { id: true },
    });
    if (!contact) return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const data = await buildContractPdfData(contract.id);
  if (!data) return NextResponse.json({ error: "Contract not found" }, { status: 404 });
  const pdfBuffer = await generateContractPdf(data);

  return new NextResponse(pdfBuffer as unknown as BodyInit, {
    status: 200,
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `attachment; filename="${contract.title.replace(/\s+/g, "-").toLowerCase()}.pdf"`,
      "Content-Length": String(pdfBuffer.length),
    },
  });
}
