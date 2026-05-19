import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalInvoicesPage({ params }: Props) {
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
    where: { organizationId: org.id, customerId: contact.customerId },
    orderBy: { createdAt: "desc" },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Invoices</h1>
      <Card>
        <CardHeader>
          <CardTitle>All invoices</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {invoices.length === 0 && <p className="text-sm text-[var(--muted-foreground)]">No invoices.</p>}
          {invoices.map((invoice) => (
            <Link key={invoice.id} href={`/portal/${orgSlug}/invoices/${invoice.id}`} className="rounded-md border border-[var(--border)] p-3 flex items-center justify-between hover:bg-[var(--muted)]">
              <div>
                <p className="font-mono font-medium">{invoice.number}</p>
                <p className="text-sm text-[var(--muted-foreground)]">Due {formatDate(invoice.dueDate)}</p>
              </div>
              <div className="text-right">
                <p className="font-medium">{formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle)}</p>
                <Badge variant={invoice.status === "PAID" ? "success" : invoice.status === "OVERDUE" || invoice.status === "CHARGEBACK" ? "destructive" : invoice.status === "REFUNDED" ? "warning" : "secondary"}>
                  {invoice.status}
                </Badge>
              </div>
            </Link>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
