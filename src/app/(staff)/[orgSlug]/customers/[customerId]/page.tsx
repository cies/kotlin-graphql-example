import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { CustomerNotes } from "@/components/customers/customer-notes";
import { EditCustomerButton } from "@/components/customers/edit-customer-button";
import { SubscriptionForm } from "@/components/invoices/subscription-form";
import { ArrowLeft, Users, FolderKanban, FileText, CreditCard } from "lucide-react";
import { formatDate, formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { createSubscription, cancelSubscription } from "@/lib/actions/subscriptions";
import type { InvoiceStatus } from "@prisma/client";
import { getCustomerRelatedCounts } from "@/lib/actions/customers";
import { CancelSubscriptionButton } from "./cancel-subscription-button";
import { DeleteContactButton } from "./delete-contact-button";

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

interface Props {
  params: Promise<{ orgSlug: string; customerId: string }>;
}

export default async function CustomerDetailPage({ params }: Props) {
  const { orgSlug, customerId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const [customer, orgSettings, member] = await Promise.all([
    prisma.customer.findUnique({
      where: { id: customerId, organizationId: org.id },
      include: {
        notificationEmails: {
          select: { id: true, purpose: true, email: true },
          orderBy: [{ purpose: "asc" }, { email: "asc" }],
        },
        contacts: { include: { user: true } },
        projects: { orderBy: { createdAt: "desc" }, take: 5 },
        customerNotes: {
          orderBy: { createdAt: "desc" },
          include: { customer: false },
        },
        invoices: {
          orderBy: { createdAt: "desc" },
          take: 10,
          include: { payments: { select: { amount: true, currency: true } } },
        },
        subscriptions: { orderBy: { createdAt: "desc" } },
        _count: { select: { invoices: true } },
      },
    }),
    prisma.orgSettings.findUnique({
      where: { organizationId: org.id },
      select: { privacyGdpr: true, privacyCcpa: true, numberFormatStyle: true },
    }),
    prisma.organizationMember.findUnique({
      where: {
        organizationId_userId: { organizationId: org.id, userId: session.user.id },
      },
      select: { role: true },
    }),
  ]);

  if (!customer) notFound();

  const isAdmin = member?.role === "OWNER" || member?.role === "ADMIN";
  const numberFormatStyle = normalizeNumberFormatStyle(orgSettings?.numberFormatStyle);

  const relatedCounts = await getCustomerRelatedCounts(orgSlug, customerId);

  const displayName =
    customer.type === "B2B"
      ? customer.companyName || "Unnamed"
      : `${customer.firstName || ""} ${customer.lastName || ""}`.trim() || "Unnamed";

  const addr = customer.billingAddress as Record<string, string> | null;

  return (
    <div>
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/customers`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold">{displayName}</h1>
            <Badge variant={customer.type === "B2B" ? "info" : "secondary"}>
              {customer.type}
            </Badge>
          </div>
          <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
            Customer since {formatDate(customer.createdAt)}
          </p>
        </div>
        <EditCustomerButton
          orgSlug={orgSlug}
          customer={{ id: customer.id, anonymizedAt: customer.anonymizedAt }}
          privacyGdpr={orgSettings?.privacyGdpr ?? false}
          privacyCcpa={orgSettings?.privacyCcpa ?? false}
          relatedCounts={relatedCounts}
        />
      </div>

      {customer.anonymizedAt && (
        <div className="mb-4 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 flex items-center gap-2">
          <span className="font-medium">PII erased</span> - Personal data was removed on{" "}
          {formatDate(customer.anonymizedAt)}. Business records are retained.
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2 space-y-6">
          {/* Notes */}
          <CustomerNotes
            orgSlug={orgSlug}
            customerId={customer.id}
            notes={customer.customerNotes}
          />

          {/* Projects */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <FolderKanban className="h-4 w-4" />
                Projects
              </CardTitle>
              <Button variant="outline" size="sm" asChild>
                <Link href={`/${orgSlug}/projects/new?customerId=${customer.id}`}>
                  New project
                </Link>
              </Button>
            </CardHeader>
            <CardContent>
              {customer.projects.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No projects yet.</p>
              ) : (
                <div className="space-y-2">
                  {customer.projects.map((p) => (
                    <Link
                      key={p.id}
                      href={`/${orgSlug}/projects/${p.id}`}
                      className="flex items-center justify-between rounded-md p-2 hover:bg-[var(--muted)] transition-colors"
                    >
                      <span className="text-sm font-medium">{p.name}</span>
                      <Badge variant="outline">{p.billingMode}</Badge>
                    </Link>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Invoices */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <FileText className="h-4 w-4" />
                Invoices
              </CardTitle>
              <Button variant="outline" size="sm" asChild>
                <Link href={`/${orgSlug}/invoices/new?customerId=${customer.id}`}>
                  New invoice
                </Link>
              </Button>
            </CardHeader>
            <CardContent>
              {customer.invoices.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No invoices yet.</p>
              ) : (
                <div className="space-y-2">
                  {customer.invoices.map((inv) => (
                    <Link
                      key={inv.id}
                      href={`/${orgSlug}/invoices/${inv.id}`}
                      className="flex items-center justify-between rounded-md p-2 hover:bg-[var(--muted)] transition-colors"
                    >
                      <div>
                        <span className="text-sm font-medium font-mono">{inv.number}</span>
                        <span className="text-xs text-[var(--muted-foreground)] ml-2">
                          {formatDate(inv.issuedAt)}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        <Badge variant={STATUS_VARIANTS[inv.status]}>{inv.status}</Badge>
                        <span className="text-sm font-medium">
                          {formatCurrency(inv.total.toString(), inv.currency, numberFormatStyle)}
                        </span>
                      </div>
                    </Link>
                  ))}
                  {customer._count.invoices > 10 && (
                    <Link
                      href={`/${orgSlug}/invoices?customerId=${customer.id}`}
                      className="text-sm text-[var(--primary)] hover:underline block pt-1"
                    >
                      View all {customer._count.invoices} invoices →
                    </Link>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Account Statement */}
          {customer.invoices.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <CreditCard className="h-4 w-4" />
                  Account Statement
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-[var(--border)]">
                        <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Invoice</th>
                        <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Date</th>
                        <th className="text-left py-2 font-medium text-[var(--muted-foreground)]">Status</th>
                        <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Invoiced</th>
                        <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Paid</th>
                        <th className="text-right py-2 font-medium text-[var(--muted-foreground)]">Balance</th>
                      </tr>
                    </thead>
                    <tbody>
                      {customer.invoices
                        .filter((i) => i.status !== "VOID" && i.status !== "DRAFT")
                        .map((inv) => {
                          const paid = parseFloat(inv.amountPaid.toString());
                          const total = parseFloat(inv.total.toString());
                          const balance = total - paid;
                          return (
                            <tr key={inv.id} className="border-b border-[var(--border)] last:border-0">
                              <td className="py-2">
                                <Link href={`/${orgSlug}/invoices/${inv.id}`} className="hover:underline font-mono text-xs">
                                  {inv.number}
                                </Link>
                              </td>
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
                        <td colSpan={3} className="py-2">Total outstanding</td>
                        <td className="py-2 text-right">
                          {formatCurrency(
                            customer.invoices
                              .filter((i) => !["VOID", "DRAFT"].includes(i.status))
                              .reduce((s, i) => s + parseFloat(i.total.toString()), 0),
                            customer.preferredCurrency,
                            numberFormatStyle
                          )}
                        </td>
                        <td className="py-2 text-right text-green-700">
                          {formatCurrency(
                            customer.invoices
                              .filter((i) => !["VOID", "DRAFT"].includes(i.status))
                              .reduce((s, i) => s + parseFloat(i.amountPaid.toString()), 0),
                            customer.preferredCurrency,
                            numberFormatStyle
                          )}
                        </td>
                        <td className="py-2 text-right text-[var(--destructive)]">
                          {formatCurrency(
                            customer.invoices
                              .filter((i) => !["VOID", "DRAFT"].includes(i.status))
                              .reduce(
                                (s, i) =>
                                  s +
                                  parseFloat(i.total.toString()) -
                                  parseFloat(i.amountPaid.toString()),
                                0
                              ),
                            customer.preferredCurrency,
                            numberFormatStyle
                          )}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Subscriptions */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <CreditCard className="h-4 w-4" />
                Stripe Subscriptions
              </CardTitle>
              <SubscriptionForm
                customerId={customer.id}
                onCreate={async (data) => {
                  "use server";
                  return createSubscription(orgSlug, data);
                }}
              />
            </CardHeader>
            <CardContent>
              {customer.subscriptions.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No subscriptions yet.</p>
              ) : (
                <div className="space-y-2">
                  {customer.subscriptions.map((sub) => (
                    <div
                      key={sub.id}
                      className="flex items-center justify-between rounded-md border border-[var(--border)] p-3"
                    >
                      <div>
                        <p className="text-sm font-medium font-mono">
                          {sub.stripeSubscriptionId ?? "Local"}
                        </p>
                        <p className="text-xs text-[var(--muted-foreground)]">
                          {sub.currentPeriodStart
                            ? `${formatDate(sub.currentPeriodStart)} – ${formatDate(sub.currentPeriodEnd)}`
                            : "No period set"}
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <Badge
                          variant={
                            sub.status === "active"
                              ? "success"
                              : sub.status === "past_due"
                              ? "destructive"
                              : "secondary"
                          }
                        >
                          {sub.status}
                        </Badge>
                        {sub.status === "active" && (
                          <CancelSubscriptionButton
                            className="text-xs text-[var(--muted-foreground)] hover:text-red-600 underline"
                            action={async () => {
                              "use server";
                              await cancelSubscription(orgSlug, sub.id);
                            }}
                          />
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        <div className="space-y-6">
          {/* Details */}
          <Card>
            <CardHeader>
              <CardTitle>Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              {customer.email && (
                <div>
                  <p className="text-[var(--muted-foreground)]">Email</p>
                  <p className="font-medium">{customer.email}</p>
                </div>
              )}
              {(() => {
                const inv = customer.notificationEmails.filter((n) => n.purpose === "INVOICES");
                const dig = customer.notificationEmails.filter((n) => n.purpose === "DIGEST");
                const con = customer.notificationEmails.filter((n) => n.purpose === "CONTRACTS");
                if (inv.length === 0 && dig.length === 0 && con.length === 0) return null;
                return (
                  <div className="space-y-2 pt-1">
                    <p className="text-[var(--muted-foreground)]">Notification routing</p>
                    {inv.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--muted-foreground)]">Invoices &amp; receipts</p>
                        <ul className="list-disc list-inside font-medium">
                          {inv.map((n) => (
                            <li key={n.id}>{n.email}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {dig.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--muted-foreground)]">Status digest</p>
                        <ul className="list-disc list-inside font-medium">
                          {dig.map((n) => (
                            <li key={n.id}>{n.email}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {con.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--muted-foreground)]">Contracts</p>
                        <ul className="list-disc list-inside font-medium">
                          {con.map((n) => (
                            <li key={n.id}>{n.email}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                );
              })()}
              {customer.phone && (
                <div>
                  <p className="text-[var(--muted-foreground)]">Phone</p>
                  <p className="font-medium">{customer.phone}</p>
                </div>
              )}
              {customer.vat && (
                <div>
                  <p className="text-[var(--muted-foreground)]">VAT</p>
                  <p className="font-medium">{customer.vat}</p>
                </div>
              )}
              <div>
                <p className="text-[var(--muted-foreground)]">Currency</p>
                <p className="font-medium">{customer.preferredCurrency}</p>
              </div>
              {addr && (
                <>
                  <Separator />
                  <div>
                    <p className="text-[var(--muted-foreground)]">Billing Address</p>
                    <div className="font-medium mt-1">
                      {addr.line1 && <p>{addr.line1}</p>}
                      {addr.line2 && <p>{addr.line2}</p>}
                      {(addr.postalCode || addr.city) && (
                        <p>{[addr.postalCode, addr.city].filter(Boolean).join(" ")}</p>
                      )}
                      {addr.country && <p>{addr.country}</p>}
                    </div>
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          {/* Contacts */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <Users className="h-4 w-4" />
                Contacts
              </CardTitle>
              {(customer.type === "B2B" ||
                customer.contacts.length === 0 ||
                isAdmin) && (
                <Button variant="outline" size="sm" asChild>
                  <Link href={`/${orgSlug}/customers/${customer.id}/contacts/new`}>
                    Add
                  </Link>
                </Button>
              )}
            </CardHeader>
            <CardContent>
              {customer.contacts.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">No contacts yet.</p>
              ) : (
                <div className="space-y-2">
                  {customer.contacts.map((c) => (
                    <div key={c.id} className="flex items-center gap-2 rounded-md border border-[var(--border)] px-3 py-2">
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium truncate">{c.user.name}</p>
                        <p className="text-xs text-[var(--muted-foreground)] truncate">{c.user.email}</p>
                      </div>
                      {c.isPrimary && (
                        <Badge variant="secondary" className="text-xs shrink-0">Primary</Badge>
                      )}
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-7 w-7 text-[var(--muted-foreground)] hover:text-[var(--foreground)] shrink-0"
                        asChild
                      >
                        <Link
                          href={`/${orgSlug}/customers/${customer.id}/contacts/${c.id}/edit`}
                          aria-label="Edit contact"
                        >
                          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                        </Link>
                      </Button>
                      <DeleteContactButton
                        orgSlug={orgSlug}
                        customerId={customer.id}
                        contactId={c.id}
                      />
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
