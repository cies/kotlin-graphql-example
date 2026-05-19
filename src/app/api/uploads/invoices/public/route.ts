import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/db/prisma";
import fs from "fs/promises";
import path from "path";
import { nanoid } from "nanoid";

export const runtime = "nodejs";

const MAX_SIZE_BYTES = 25 * 1024 * 1024;
const ALLOWED_MIMES = new Set([
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
  "image/svg+xml",
  "application/pdf",
  "text/plain",
  "text/csv",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/zip",
]);

async function resolveInvoiceByToken(token: string | null) {
  if (!token) return null;
  return prisma.invoice.findUnique({
    where: { viewToken: token },
    select: { id: true, organizationId: true, status: true },
  });
}

export async function POST(req: NextRequest) {
  const formData = await req.formData();
  const token = (formData.get("token") as string | null)?.trim() || null;
  const file = formData.get("file") as File | null;

  const invoice = await resolveInvoiceByToken(token);
  if (!invoice || invoice.status === "DRAFT" || invoice.status === "VOID") {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  if (!file) {
    return NextResponse.json({ error: "No file provided" }, { status: 400 });
  }

  if (file.size > MAX_SIZE_BYTES) {
    return NextResponse.json({ error: "File too large (max 25 MB)" }, { status: 413 });
  }

  if (!ALLOWED_MIMES.has(file.type)) {
    return NextResponse.json({ error: "File type not allowed" }, { status: 415 });
  }

  const safeFilename = file.name.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 200);
  const uniqueName = `${nanoid(12)}-${safeFilename}`;
  const relativePath = path.join("uploads", "invoices", invoice.organizationId, invoice.id, uniqueName);
  const fullPath = path.join(process.cwd(), relativePath);

  await fs.mkdir(path.dirname(fullPath), { recursive: true });
  const arrayBuf = await file.arrayBuffer();
  await fs.writeFile(fullPath, Buffer.from(arrayBuf));

  const attachment = await prisma.invoiceAttachment.create({
    data: {
      organizationId: invoice.organizationId,
      invoiceId: invoice.id,
      uploadedBy: "PUBLIC",
      filename: file.name,
      mimeType: file.type,
      sizeBytes: file.size,
      storagePath: relativePath,
    },
  });

  return NextResponse.json({ success: true, attachmentId: attachment.id });
}

export async function GET(req: NextRequest) {
  const token = req.nextUrl.searchParams.get("token")?.trim() || null;
  const attachmentId = req.nextUrl.searchParams.get("attachmentId")?.trim() || null;

  if (!token || !attachmentId) {
    return NextResponse.json({ error: "token and attachmentId required" }, { status: 400 });
  }

  const invoice = await resolveInvoiceByToken(token);
  if (!invoice || invoice.status === "DRAFT") {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const attachment = await prisma.invoiceAttachment.findUnique({
    where: { id: attachmentId, invoiceId: invoice.id },
  });
  if (!attachment) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
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
