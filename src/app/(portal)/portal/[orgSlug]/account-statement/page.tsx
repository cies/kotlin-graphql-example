import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalAccountStatementPage({ params }: Props) {
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
    select: { customerId: true, canSeeInvoices: true, customer: { select: { preferredCurrency: true } } },
  });
  if (!contact || !contact.canSeeInvoices) redirect(`/portal/${orgSlug}`);

  const invoices = await prisma.invoice.findMany({
    where: { organizationId: org.id, customerId: contact.customerId, status: { notIn: ["VOID", "DRAFT"] } },
    orderBy: { issuedAt: "desc" },
  });

  const outstanding = invoices.reduce(
    (sum, inv) => sum + Number(inv.total) - Number(inv.amountPaid),
    0
  );

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Account statement</h1>
      <Card>
        <CardHeader>
          <CardTitle>Invoices and payments</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-[var(--border)]">
                  <th className="text-left py-2">Invoice</th>
                  <th className="text-left py-2">Date</th>
                  <th className="text-left py-2">Status</th>
                  <th className="text-right py-2">Invoiced</th>
                  <th className="text-right py-2">Paid</th>
                  <th className="text-right py-2">Balance</th>
                </tr>
              </thead>
              <tbody>
                {invoices.map((inv) => {
                  const balance = Number(inv.total) - Number(inv.amountPaid);
                  return (
                    <tr key={inv.id} className="border-b border-[var(--border)]">
                      <td className="py-2 font-mono text-xs">{inv.number}</td>
                      <td className="py-2">{formatDate(inv.issuedAt)}</td>
                      <td className="py-2">
                        <Badge variant={inv.status === "PAID" ? "success" : inv.status === "OVERDUE" || inv.status === "CHARGEBACK" ? "destructive" : inv.status === "REFUNDED" ? "warning" : "secondary"}>
                          {inv.status}
                        </Badge>
                      </td>
                      <td className="py-2 text-right">{formatCurrency(inv.total.toString(), inv.currency, numberFormatStyle)}</td>
                      <td className="py-2 text-right">{Number(inv.amountPaid) > 0 ? formatCurrency(inv.amountPaid.toString(), inv.currency, numberFormatStyle) : "-"}</td>
                      <td className="py-2 text-right">{balance > 0 ? formatCurrency(balance, inv.currency, numberFormatStyle) : "-"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="text-right mt-3 font-semibold">
            Outstanding: {formatCurrency(outstanding, contact.customer.preferredCurrency, numberFormatStyle)}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
