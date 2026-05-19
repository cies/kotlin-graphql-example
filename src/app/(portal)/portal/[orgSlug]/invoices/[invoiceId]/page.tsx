import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { PaymentReceiptActions } from "@/components/payments/payment-receipt-actions";
import { createPortalStripeCheckout } from "@/lib/actions/payments";
import { resolveInvoiceViewChrome } from "@/lib/invoice/invoice-view-chrome";

interface Props {
  params: Promise<{ orgSlug: string; invoiceId: string }>;
  searchParams: Promise<{ payment?: string }>;
}

export default async function PortalInvoiceDetailPage({ params, searchParams }: Props) {
  const { orgSlug, invoiceId } = await params;
  const { payment } = await searchParams;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: {
      id: true,
      settings: {
        select: {
          stripeSecretKey: true,
          numberFormatStyle: true,
          emailAccentColor: true,
          invoiceAccentColor: true,
        },
      },
    },
  });
  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: { customerId: true, canSeeInvoices: true, canPayInvoices: true },
  });
  if (!contact || !contact.canSeeInvoices) redirect(`/portal/${orgSlug}`);

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: org.id, customerId: contact.customerId },
    include: {
      lines: { orderBy: { sortOrder: "asc" } },
      payments: { orderBy: { paidAt: "desc" } },
    },
  });
  if (!invoice) redirect(`/portal/${orgSlug}/invoices`);

  const outstanding = Number(invoice.total) - Number(invoice.amountPaid);
  const numberFormatStyle = normalizeNumberFormatStyle(org.settings?.numberFormatStyle);
  const canPay =
    contact.canPayInvoices &&
    !!org.settings?.stripeSecretKey &&
    outstanding > 0 &&
    !["PAID", "VOID", "DRAFT", "REFUNDED", "CHARGEBACK"].includes(invoice.status);

  const { chrome } = resolveInvoiceViewChrome(org.settings);

  return (
    <div className="mx-auto w-full max-w-6xl space-y-5 px-4 sm:px-6">
      {payment === "success" && (
        <div className="rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-700">
          Payment completed successfully.
        </div>
      )}
      <div
        className="flex flex-wrap items-center justify-between gap-3 rounded-lg border px-5 py-4 sm:px-6"
        style={{
          backgroundColor: chrome.datesBg,
          borderColor: chrome.datesBorder,
        }}
      >
        <h1 className="text-3xl font-bold font-mono tracking-tight" style={{ color: chrome.headerCellText }}>
          {invoice.number}
        </h1>
        <Badge
          variant={
            invoice.status === "PAID"
              ? "success"
              : invoice.status === "OVERDUE" || invoice.status === "CHARGEBACK"
                ? "destructive"
                : invoice.status === "REFUNDED"
                  ? "warning"
                  : "secondary"
          }
        >
          {invoice.status}
        </Badge>
      </div>

      <Card className="overflow-hidden shadow-sm">
        <CardHeader
          className="border-b py-4"
          style={{ backgroundColor: chrome.tableHeaderBg, borderColor: chrome.tableRowBorder }}
        >
          <CardTitle className="text-lg" style={{ color: chrome.headerCellText }}>
            Invoice
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 pt-4">
          <p className="text-base" style={{ color: chrome.headerCellText, opacity: 0.85 }}>
            Issued {formatDate(invoice.issuedAt)} · Due {formatDate(invoice.dueDate)}
          </p>
          {invoice.lines.map((line, rowIdx) => (
            <div
              key={line.id}
              className="rounded-md border p-3 text-base flex items-center justify-between"
              style={{
                backgroundColor: rowIdx % 2 === 1 ? chrome.tableRowAltBg : undefined,
                borderColor: chrome.tableRowBorder,
              }}
            >
              <span>
                <span className="font-medium">{line.name}</span>
                {line.description && (
                  <span className="block text-xs text-[var(--muted-foreground)] mt-0.5">{line.description}</span>
                )}
              </span>
              <span>{formatCurrency(line.total.toString(), invoice.currency, numberFormatStyle)}</span>
            </div>
          ))}
          <div
            className="text-right space-y-1.5 pt-2 -mx-6 px-6 py-4 rounded-b-md text-base"
            style={{ backgroundColor: chrome.grandBg, color: "#ffffff" }}
          >
            <p>Total: {formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle)}</p>
            <p>Paid: {formatCurrency(invoice.amountPaid.toString(), invoice.currency, numberFormatStyle)}</p>
            <p className="font-medium">
              Balance: {outstanding > 0 ? formatCurrency(outstanding, invoice.currency, numberFormatStyle) : "-"}
            </p>
          </div>
          <div className="flex gap-2 flex-wrap">
            <Button variant="outline" asChild>
              <a
                href={`/api/pdf/invoice/${invoice.id}?preview=1`}
                target="_blank"
                rel="noreferrer"
              >
                View Invoice
              </a>
            </Button>
            <Button variant="outline" asChild>
              <a href={`/api/pdf/invoice/${invoice.id}`} target="_blank" rel="noreferrer">
                Download PDF
              </a>
            </Button>
            {canPay && (
              <form
                action={async () => {
                  "use server";
                  const result = await createPortalStripeCheckout(orgSlug, invoice.id);
                  if (result.url) redirect(result.url);
                }}
              >
                <Button type="submit">Pay now</Button>
              </form>
            )}
          </div>
        </CardContent>
      </Card>

      {invoice.payments.length > 0 && (
        <Card className="overflow-hidden shadow-sm">
          <CardHeader
            className="border-b py-4"
            style={{ backgroundColor: chrome.tableHeaderBg, borderColor: chrome.tableRowBorder }}
          >
            <CardTitle className="text-lg" style={{ color: chrome.headerCellText }}>
              Payments
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 pt-4">
            {invoice.payments.map((p, rowIdx) => (
              <div
                key={p.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-4 text-base"
                style={{
                  backgroundColor: rowIdx % 2 === 1 ? chrome.tableRowAltBg : undefined,
                  borderColor: chrome.tableRowBorder,
                }}
              >
                <div>
                  <p className="font-medium">
                    {formatCurrency(p.amount.toString(), p.currency, numberFormatStyle)}
                  </p>
                  <p className="text-xs text-[var(--muted-foreground)]">
                    {formatDate(p.paidAt)} · {p.method.replace("_", " ")}
                    {(p.stripeChargeId || p.notes) && (
                      <span className="ml-1 font-mono">
                        · {p.stripeChargeId ?? p.notes}
                      </span>
                    )}
                  </p>
                </div>
                <PaymentReceiptActions
                  orgSlug={orgSlug}
                  paymentId={p.id}
                  receiptViewHref={`/portal/${orgSlug}/receipt/${p.id}`}
                  allowResend={false}
                />
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
