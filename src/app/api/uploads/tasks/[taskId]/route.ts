import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import fs from "fs/promises";
import path from "path";
import { nanoid } from "nanoid";

export const runtime = "nodejs";

const MAX_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB
const ALLOWED_MIMES = new Set([
  "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
  "application/pdf",
  "text/plain", "text/csv",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/zip",
]);

async function getAuthorizedOrgId(
  req: NextRequest,
  taskId: string
): Promise<{ orgId: string; userId: string } | null> {
  const session = await auth();
  if (!session?.user) return null;

  const task = await prisma.task.findUnique({
    where: { id: taskId },
    select: { organizationId: true, project: { select: { id: true } } },
  });
  if (!task) return null;

  if (session.user.userType === "STAFF") {
    const member = await prisma.organizationMember.findFirst({
      where: { organizationId: task.organizationId, userId: session.user.id },
    });
    if (!member) return null;
    return { orgId: task.organizationId, userId: session.user.id };
  }

  if (session.user.userType === "CUSTOMER_CONTACT") {
    const contact = await prisma.customerContact.findFirst({
      where: { organizationId: task.organizationId, userId: session.user.id, canSeeTasks: true },
    });
    if (!contact) return null;
    return { orgId: task.organizationId, userId: session.user.id };
  }

  return null;
}

export async function POST(
  req: NextRequest,
  { params }: { params: Promise<{ taskId: string }> }
) {
  const { taskId } = await params;

  const auth_ = await getAuthorizedOrgId(req, taskId);
  if (!auth_) return NextResponse.json({ error: "Unauthorized" }, { status: 401 });

  const { orgId, userId } = auth_;

  const formData = await req.formData();
  const file = formData.get("file") as File | null;
  if (!file) return NextResponse.json({ error: "No file provided" }, { status: 400 });

  if (file.size > MAX_SIZE_BYTES) {
    return NextResponse.json({ error: "File too large (max 25 MB)" }, { status: 413 });
  }

  if (!ALLOWED_MIMES.has(file.type)) {
    return NextResponse.json({ error: "File type not allowed" }, { status: 415 });
  }

  // Sanitise filename
  const safeFilename = file.name.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 200);
  const uniqueName = `${nanoid(12)}-${safeFilename}`;
  const relativePath = path.join("uploads", "tasks", orgId, taskId, uniqueName);
  const fullPath = path.join(process.cwd(), relativePath);

  await fs.mkdir(path.dirname(fullPath), { recursive: true });
  const arrayBuf = await file.arrayBuffer();
  await fs.writeFile(fullPath, Buffer.from(arrayBuf));

  const attachment = await prisma.taskAttachment.create({
    data: {
      organizationId: orgId,
      taskId,
      uploaderId: userId,
      filename: file.name,
      mimeType: file.type,
      sizeBytes: file.size,
      storagePath: relativePath,
    },
  });

  return NextResponse.json({ success: true, attachmentId: attachment.id });
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ taskId: string }> }
) {
  const { taskId } = await params;

  const attachmentId = req.nextUrl.searchParams.get("attachmentId");
  if (!attachmentId) return NextResponse.json({ error: "attachmentId required" }, { status: 400 });

  const auth_ = await getAuthorizedOrgId(req, taskId);
  if (!auth_) return NextResponse.json({ error: "Unauthorized" }, { status: 401 });

  const attachment = await prisma.taskAttachment.findUnique({
    where: { id: attachmentId, taskId },
  });
  if (!attachment) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const fullPath = path.join(process.cwd(), attachment.storagePath);
  let buf: Buffer;
  try {
    buf = await fs.readFile(fullPath);
  } catch {
    return NextResponse.json({ error: "File not found on disk" }, { status: 404 });
  }

  return new NextResponse(buf as unknown as BodyInit, {
    headers: {
      "Content-Type": attachment.mimeType,
      "Content-Disposition": `attachment; filename="${attachment.filename}"`,
      "Content-Length": String(buf.length),
    },
  });
}
