import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect, notFound } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { getPaymentWithReceiptContext, toReceiptPdfData } from "@/lib/receipt/resolve-receipt-pdf-data";
import { receiptDetailsHtml } from "@/lib/receipt/receipt-details-html";
import { ArrowLeft } from "lucide-react";

export const metadata = { title: "Payment receipt" };

interface Props {
  params: Promise<{ orgSlug: string; paymentId: string }>;
}

export default async function StaffReceiptViewPage({ params }: Props) {
  const { orgSlug, paymentId } = await params;
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) notFound();

  if (session.user.organizationId !== org.id) redirect("/auth/login");

  const payment = await getPaymentWithReceiptContext(paymentId);
  if (!payment || payment.organizationId !== org.id) notFound();

  const data = toReceiptPdfData(payment);
  const html = receiptDetailsHtml(data);

  return (
    <div className="max-w-3xl mx-auto py-8 px-4">
      <div className="flex flex-wrap items-center gap-3 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/invoices/${payment.invoice.id}`} aria-label="Back to invoice">
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <h1 className="text-xl font-semibold flex-1">Payment receipt</h1>
        <Button variant="outline" size="sm" asChild>
          <a
            href={`/api/pdf/receipt/payment/${paymentId}?preview=1`}
            target="_blank"
            rel="noopener noreferrer"
          >
            Open PDF
          </a>
        </Button>
        <Button size="sm" asChild>
          <a href={`/api/pdf/receipt/payment/${paymentId}`} target="_blank" rel="noopener noreferrer">
            Download PDF
          </a>
        </Button>
      </div>
      <div
        className="rounded-lg border border-[var(--border)] bg-[var(--card)] p-6 sm:p-10 shadow-sm"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  );
}
