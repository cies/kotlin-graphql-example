import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalReceiptsPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, settings: { select: { numberFormatStyle: true } } },
  });
  if (!org) redirect("/auth/login");
  const numberFormatStyle = normalizeNumberFormatStyle(org.settings?.numberFormatStyle);

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: { customerId: true, canSeeInvoices: true },
  });
  if (!contact || !contact.canSeeInvoices) redirect(`/portal/${orgSlug}`);

  const invoices = await prisma.invoice.findMany({
    where: { organizationId: org.id, customerId: contact.customerId, status: "PAID" },
    include: { payments: { orderBy: { paidAt: "desc" }, take: 1 } },
    orderBy: { paidAt: "desc" },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Receipts</h1>
      <Card>
        <CardHeader>
          <CardTitle>Paid invoices</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {invoices.length === 0 && <p className="text-sm text-[var(--muted-foreground)]">No receipts yet.</p>}
          {invoices.map((invoice) => (
            <div key={invoice.id} className="rounded-md border border-[var(--border)] p-3 flex items-center justify-between">
              <div>
                <p className="font-mono font-medium">{invoice.number}</p>
                <p className="text-sm text-[var(--muted-foreground)]">
                  Paid {formatDate(invoice.paidAt || invoice.payments[0]?.paidAt)}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className="font-medium">{formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle)}</span>
                <a className="text-sm underline" href={`/api/pdf/receipt/${invoice.id}`} target="_blank" rel="noreferrer">
                  Receipt PDF
                </a>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
