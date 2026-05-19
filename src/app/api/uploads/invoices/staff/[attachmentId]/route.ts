import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import fs from "fs/promises";
import path from "path";

export const runtime = "nodejs";

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ attachmentId: string }> }
) {
  const orgSlug = req.nextUrl.searchParams.get("orgSlug")?.trim();
  if (!orgSlug) {
    return NextResponse.json({ error: "orgSlug required" }, { status: 400 });
  }

  const { attachmentId } = await params;

  const session = await auth();
  if (!session?.user) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  const attachment = await prisma.invoiceAttachment.findUnique({
    where: { id: attachmentId, organizationId: org.id },
  });
  if (!attachment) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  if (session.user.userType === "STAFF") {
    const member = await prisma.organizationMember.findUnique({
      where: {
        organizationId_userId: { organizationId: org.id, userId: session.user.id },
      },
    });
    if (!member) {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }
  } else if (session.user.userType === "CUSTOMER_CONTACT") {
    const contact = await prisma.customerContact.findFirst({
      where: {
        organizationId: org.id,
        userId: session.user.id,
        canSeeInvoices: true,
        customer: {
          invoices: { some: { id: attachment.invoiceId } },
        },
      },
    });
    if (!contact) {
      return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }
  } else {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const fullPath = path.join(process.cwd(), attachment.storagePath);
  let buf: Buffer;
  try {
    buf = await fs.readFile(fullPath);
  } catch {
    return NextResponse.json({ error: "File not found on disk" }, { status: 404 });
  }

  return new NextResponse(new Uint8Array(buf), {
    headers: {
      "Content-Type": attachment.mimeType,
      "Content-Disposition": `attachment; filename="${attachment.filename.replace(/"/g, "")}"`,
      "Content-Length": String(buf.length),
    },
  });
}
