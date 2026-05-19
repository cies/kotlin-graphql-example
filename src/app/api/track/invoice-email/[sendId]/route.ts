import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/db/prisma";
import { assertRateLimit } from "@/lib/rate-limit/redis";

/** Minimal transparent 1×1 GIF */
const GIF = Buffer.from(
  "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
  "base64"
);

function clientIp(req: NextRequest): string | undefined {
  const fwd = req.headers.get("x-forwarded-for");
  if (fwd) return fwd.split(",")[0]?.trim() || undefined;
  return req.headers.get("x-real-ip") || undefined;
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ sendId: string }> }
) {
  const { sendId } = await params;

  const send = await prisma.invoiceEmailSend.findUnique({
    where: { id: sendId },
    select: { id: true },
  });

  if (send) {
    const allowed = await assertRateLimit(`invoice-email-pixel:${sendId}`, 80, 60);
    if (allowed) {
      const ip = clientIp(req);
      const ua = req.headers.get("user-agent") || undefined;
      const minuteAgo = new Date(Date.now() - 60_000);

      const recent = await prisma.invoiceEmailOpen.findFirst({
        where: {
          sendId,
          ...(ip ? { ipAddress: ip } : {}),
          openedAt: { gte: minuteAgo },
        },
        select: { id: true },
      });

      if (!recent) {
        try {
          await prisma.invoiceEmailOpen.create({
            data: {
              sendId,
              ipAddress: ip ?? null,
              userAgent: ua ?? null,
            },
          });
        } catch {
          // best-effort
        }
      }
    }
  }

  return new NextResponse(new Uint8Array(GIF), {
    status: 200,
    headers: {
      "Content-Type": "image/gif",
      "Cache-Control": "no-store, no-cache, must-revalidate, private",
      "Content-Length": String(GIF.length),
    },
  });
}
