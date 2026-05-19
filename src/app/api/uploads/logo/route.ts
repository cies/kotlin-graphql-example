import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import path from "path";
import fs from "fs/promises";

const MAX_SIZE_BYTES = 2 * 1024 * 1024; // 2 MB
const ALLOWED_MIMES = new Set([
  "image/png",
  "image/jpeg",
  "image/jpg",
  "image/svg+xml",
  "image/webp",
  "image/gif",
]);

export async function POST(req: NextRequest) {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const orgSlug = req.nextUrl.searchParams.get("orgSlug");
  if (!orgSlug) return NextResponse.json({ error: "Missing orgSlug" }, { status: 400 });

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: org.id, userId: session.user.id } },
    select: { role: true },
  });
  if (!member || !["OWNER", "ADMIN"].includes(member.role)) {
    return NextResponse.json({ error: "Forbidden" }, { status: 403 });
  }

  const formData = await req.formData();
  const file = formData.get("file") as File | null;
  if (!file) return NextResponse.json({ error: "No file provided" }, { status: 400 });

  if (file.size > MAX_SIZE_BYTES) {
    return NextResponse.json({ error: "File too large (max 2 MB)" }, { status: 413 });
  }
  if (!ALLOWED_MIMES.has(file.type)) {
    return NextResponse.json({ error: "Invalid file type. PNG, SVG, JPG or WebP only." }, { status: 415 });
  }

  const ext = file.name.split(".").pop()?.toLowerCase() ?? "png";
  const filename = `logo.${ext}`;
  const relDir = path.join("uploads", "logos", org.id);
  const relPath = path.join(relDir, filename);
  const fullDir = path.join(process.cwd(), relDir);
  const fullPath = path.join(process.cwd(), relPath);

  await fs.mkdir(fullDir, { recursive: true });
  const buf = Buffer.from(await file.arrayBuffer());
  await fs.writeFile(fullPath, buf);

  // Return a URL that points to our serve route
  const url = `/api/uploads/logo/${org.id}/${filename}`;
  return NextResponse.json({ url });
}
