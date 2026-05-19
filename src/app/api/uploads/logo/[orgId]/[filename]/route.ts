import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs/promises";

const MIME: Record<string, string> = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  svg: "image/svg+xml",
  webp: "image/webp",
  gif: "image/gif",
};

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ orgId: string; filename: string }> }
) {
  const { orgId, filename } = await params;

  // Prevent path traversal
  if (orgId.includes("..") || filename.includes("..")) {
    return new NextResponse("Not found", { status: 404 });
  }

  const safeName = path.basename(filename);
  const fullPath = path.join(process.cwd(), "uploads", "logos", orgId, safeName);

  try {
    const buf = await fs.readFile(fullPath);
    const ext = safeName.split(".").pop()?.toLowerCase() ?? "png";
    const contentType = MIME[ext] ?? "application/octet-stream";

    return new NextResponse(buf, {
      headers: {
        "Content-Type": contentType,
        "Cache-Control": "public, max-age=31536000, immutable",
      },
    });
  } catch {
    return new NextResponse("Not found", { status: 404 });
  }
}
