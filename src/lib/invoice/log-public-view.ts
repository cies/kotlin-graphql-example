import { headers } from "next/headers";
import { prisma } from "@/lib/db/prisma";

/** Best-effort log when someone loads the public invoice page (token URL). */
export async function logInvoicePublicView(invoiceId: string): Promise<void> {
  try {
    const h = await headers();
    const ip =
      h.get("x-forwarded-for")?.split(",")[0]?.trim() || h.get("x-real-ip") || null;
    const ua = h.get("user-agent") || null;
    await prisma.invoicePublicView.create({
      data: {
        invoiceId,
        ipAddress: ip,
        userAgent: ua,
      },
    });
  } catch (err) {
    console.error("[InvoicePublicView] log failed:", err);
  }
}
