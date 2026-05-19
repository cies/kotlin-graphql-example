import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { createPortalStripeCheckout } from "@/lib/actions/payments";
import type { InvoiceStatus } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<{ payment?: string }>;
}

const STATUS_VARIANTS: Record<
  InvoiceStatus,
  "default" | "secondary" | "info" | "success" | "warning" | "destructive"
> = {
  DRAFT: "secondary",
  SENT: "info",
  PARTIAL: "warning",
  PAID: "success",
  OVERDUE: "destructive",
  VOID: "secondary",
  REFUNDED: "warning",
  CHARGEBACK: "destructive",
};

export default async function PortalDashboard({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const { payment: paymentStatus } = await searchParams;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect(`/portal/${orgSlug}/login`);

  const contact = await prisma.customerContact.findFirst({
    where: { userId: session.user.id, organizationId: org.id },
    include: { customer: true },
  });

  if (!contact) redirect(`/portal/${orgSlug}/login`);

  const hasStripe = !!(org.settings?.stripeSecretKey);
  const numberFormatStyle = normalizeNumberFormatStyle(
    (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
  );

  const [projects, invoices] = await Promise.all([
    contact.canSeeProjects
      ? prisma.project.findMany({
          where: { customerId: contact.customerId },
          orderBy: { createdAt: "desc" },
          take: 5,
        })
      : [],
    contact.canSeeInvoices
      ? prisma.invoice.findMany({
          where: { customerId: contact.customerId },
          orderBy: { createdAt: "desc" },
          include: { payments: { select: { amount: true } } },
        })
      : [],
  ]);

  const customerName =
    contact.customer.companyName ||
    `${contact.customer.firstName || ""} ${contact.customer.lastName || ""}`.trim();

  const totalOutstanding = invoices
    .filter((i) => ["SENT", "PARTIAL", "OVERDUE"].includes(i.status))
    .reduce(
      (sum, i) =>
        sum + parseFloat(i.total.toString()) - parseFloat(i.amountPaid.toString()),
      0
    );

  return (
    <div>
      {paymentStatus === "success" && (
        <div className="mb-4 rounded-md bg-green-50 border border-green-200 p-4 text-green-800 text-sm font-medium">
          Payment received successfully! Thank you.
        </div>
      )}

      <div className="mb-6">
        <h1 className="text-2xl font-bold">
          Welcome, {session.user.name || session.user.email}
        </h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">{customerName}</p>
        {totalOutstanding > 0 && (
          <p className="text-orange-600 text-sm mt-1 font-medium">
            Outstanding balance: {formatCurrency(totalOutstanding, contact.customer.preferredCurrency, numberFormatStyle)}
          </p>
        )}
      </div>

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        {contact.canSeeProjects && (
          <Card>
            <CardHeader>
              <CardTitle>Projects</CardTitle>
            </CardHeader>
            <CardContent>
              {projects.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No projects.</p>
              ) : (
                <div className="space-y-2">
                  {projects.map((p) => (
                    <div key={p.id} className="flex items-center justify-between py-1">
                      <span className="text-sm font-medium">{p.name}</span>
                      <Badge variant="outline">{p.status}</Badge>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {contact.canSeeInvoices && (
          <Card>
            <CardHeader>
              <CardTitle>Invoices</CardTitle>
            </CardHeader>
            <CardContent>
              {invoices.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No invoices.</p>
              ) : (
                <div className="space-y-3">
                  {invoices.map((inv) => {
                    const amountDue =
                      parseFloat(inv.total.toString()) -
                      parseFloat(inv.amountPaid.toString());
                    const canPay =
                      contact.canPayInvoices &&
                      hasStripe &&
                      !["PAID", "VOID", "DRAFT", "REFUNDED", "CHARGEBACK"].includes(inv.status) &&
                      amountDue > 0;

                    return (
                      <div
                        key={inv.id}
                        className="flex items-center justify-between py-2 border-b border-[var(--border)] last:border-0"
                      >
                        <div>
                          <p className="text-sm font-medium font-mono">{inv.number}</p>
                          <p className="text-xs text-[var(--muted-foreground)]">
                            Due {formatDate(inv.dueDate)}
                          </p>
                        </div>
                        <div className="flex items-center gap-2">
                          <div className="text-right">
                            <p className="text-sm font-medium">
                              {formatCurrency(Number(inv.total), inv.currency, numberFormatStyle)}
                            </p>
                            <Badge
                              variant={STATUS_VARIANTS[inv.status]}
                              className="text-xs"
                            >
                              {inv.status}
                            </Badge>
                          </div>
                          <div className="flex gap-1">
                            <Button variant="ghost" size="sm" asChild>
                              <a
                                href={`/api/pdf/invoice/${inv.id}`}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="text-xs"
                              >
                                PDF
                              </a>
                            </Button>
                            {canPay && (
                              <form
                                action={async () => {
                                  "use server";
                                  const result = await createPortalStripeCheckout(orgSlug, inv.id);
                                  if (result.url) redirect(result.url);
                                }}
                              >
                                <Button type="submit" size="sm" className="text-xs">
                                  Pay Now
                                </Button>
                              </form>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* Account Statement */}
        {contact.canSeeInvoices && invoices.length > 0 && (
          <Card className="md:col-span-2">
            <CardHeader>
              <CardTitle>Account Statement</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-[var(--border)]">
                      <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Invoice</th>
                      <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Date</th>
                      <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Status</th>
                      <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Amount</th>
                      <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Paid</th>
                      <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {invoices
                      .filter((i) => !["VOID", "DRAFT"].includes(i.status))
                      .map((inv) => {
                        const paid = parseFloat(inv.amountPaid.toString());
                        const total = parseFloat(inv.total.toString());
                        const balance = total - paid;
                        return (
                          <tr key={inv.id} className="border-b border-[var(--border)] last:border-0">
                            <td className="py-2 font-mono text-xs">{inv.number}</td>
                            <td className="py-2 text-[var(--muted-foreground)]">{formatDate(inv.issuedAt)}</td>
                            <td className="py-2">
                              <Badge variant={STATUS_VARIANTS[inv.status]} className="text-xs">{inv.status}</Badge>
                            </td>
                            <td className="py-2 text-right">{formatCurrency(total, inv.currency, numberFormatStyle)}</td>
                            <td className="py-2 text-right text-green-700">{paid > 0 ? formatCurrency(paid, inv.currency, numberFormatStyle) : "-"}</td>
                            <td className="py-2 text-right font-medium" style={{ color: balance > 0 ? "var(--destructive)" : "inherit" }}>
                              {balance > 0 ? formatCurrency(balance, inv.currency, numberFormatStyle) : "-"}
                            </td>
                          </tr>
                        );
                      })}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-[var(--border)] font-semibold">
                      <td colSpan={5} className="py-2">Total outstanding</td>
                      <td className="py-2 text-right text-orange-600">
                        {formatCurrency(totalOutstanding, contact.customer.preferredCurrency, numberFormatStyle)}
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
