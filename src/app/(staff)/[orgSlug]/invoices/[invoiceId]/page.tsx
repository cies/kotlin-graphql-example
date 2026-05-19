import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Suspense } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { InvoiceActions } from "@/components/invoices/invoice-actions";
import { PublicInvoiceView } from "@/components/invoices/public-invoice-view";
import { InvoiceDetailShell } from "@/components/invoices/invoice-detail-shell";
import { InvoiceStaffAttachments } from "@/components/invoices/invoice-staff-attachments";
import { InvoiceNotesPanel } from "@/components/invoices/invoice-notes-panel";
import { InvoiceRemindersPanel } from "@/components/invoices/invoice-reminders-panel";
import {
  formatDate,
  formatDateTime,
  formatCurrency,
  normalizeNumberFormatStyle,
} from "@/lib/utils/format";
import {
  sendInvoiceEmail,
  voidInvoice,
  getInvoicePreviewUrl,
  duplicateInvoiceAsDraft,
} from "@/lib/actions/invoices";
import { buildInvoiceEmailRecipientRows } from "@/lib/invoices/invoice-email-recipients-options";
import { InvoiceDeleteVerifiedZone } from "@/components/invoices/invoice-delete-verified-zone";
import { recordManualPayment, createStripeCheckoutSession } from "@/lib/actions/payments";
import { PaymentReceiptActions } from "@/components/payments/payment-receipt-actions";
import { RefundPaymentMenu } from "@/components/payments/refund-payment-menu";
import { InvoiceRefundAllStripeButton } from "@/components/invoices/invoice-refund-stripe-all";
import { Pencil, ArrowLeft } from "lucide-react";
import type { InvoiceStatus } from "@prisma/client";
import { resolveInvoiceViewChrome } from "@/lib/invoice/invoice-view-chrome";
import { paymentHasOpenStripeDispute } from "@/lib/payments/stripe-dispute-helpers";

interface Props {
  params: Promise<{ orgSlug: string; invoiceId: string }>;
  searchParams: Promise<{ payment?: string; tab?: string }>;
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

export default async function InvoiceDetailPage({ params, searchParams }: Props) {
  const { orgSlug, invoiceId } = await params;
  const { payment: paymentStatus, tab: tabParam } = await searchParams;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  const currentMember = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
    select: { role: true },
  });
  const canDeleteInvoiceWithVerification =
    currentMember?.role === "OWNER" || currentMember?.role === "ADMIN";
  const s = org.settings;
  const invoiceDeletionSmtpReady = !!(
    s?.smtpHost?.trim() &&
    s.smtpPort &&
    s.smtpUser?.trim() &&
    s.smtpPass?.trim() &&
    s.smtpFrom?.trim()
  );

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: org.id },
    include: {
      customer: {
        include: {
          notificationEmails: true,
          contacts: {
            orderBy: [{ isPrimary: "desc" }, { id: "asc" }],
            include: {
              user: { select: { email: true, name: true } },
            },
          },
        },
      },
      lines: { orderBy: { sortOrder: "asc" } },
      payments: { orderBy: { paidAt: "desc" } },
      attachments: { orderBy: { createdAt: "desc" } },
      staffNotes: { orderBy: { createdAt: "desc" } },
    },
  });

  if (!invoice) redirect(`/${orgSlug}/invoices`);

  const taskIds = [
    ...new Set(invoice.lines.map((l) => l.taskId).filter((id): id is string => !!id)),
  ];
  const tasksWithProjects =
    taskIds.length === 0
      ? []
      : await prisma.task.findMany({
          where: { id: { in: taskIds }, organizationId: org.id },
          include: { project: { select: { id: true, name: true } } },
        });
  const taskById = new Map(tasksWithProjects.map((t) => [t.id, t]));

  const [
    emailSends,
    publicViews,
    auditRows,
    invoiceReminders,
    staffMembers,
  ] = await Promise.all([
    prisma.invoiceEmailSend.findMany({
      where: { invoiceId },
      orderBy: { sentAt: "desc" },
      include: { opens: { orderBy: { openedAt: "asc" } } },
    }),
    prisma.invoicePublicView.findMany({
      where: { invoiceId },
      orderBy: { viewedAt: "desc" },
      take: 200,
    }),
    prisma.auditLog.findMany({
      where: {
        organizationId: org.id,
        entityType: "INVOICE",
        entityId: invoiceId,
      },
      orderBy: { createdAt: "desc" },
      take: 100,
    }),
    prisma.reminder.findMany({
      where: {
        organizationId: org.id,
        targetType: "INVOICE",
        targetId: invoiceId,
      },
      orderBy: { notifyAt: "asc" },
    }),
    prisma.organizationMember.findMany({
      where: { organizationId: org.id },
      include: { user: { select: { id: true, name: true, email: true } } },
    }),
  ]);

  const customerName =
    invoice.customer.type === "B2B"
      ? invoice.customer.companyName ?? "Unnamed"
      : `${invoice.customer.firstName ?? ""} ${invoice.customer.lastName ?? ""}`.trim() || "Unnamed";

  const invoiceEmailRecipients = buildInvoiceEmailRecipientRows({
    email: invoice.customer.email,
    companyName: invoice.customer.companyName,
    contacts: invoice.customer.contacts,
    notificationEmails: invoice.customer.notificationEmails,
  });

  const amountDue =
    parseFloat(invoice.total.toString()) - parseFloat(invoice.amountPaid.toString());
  const numberFormatStyle = normalizeNumberFormatStyle(
    (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
  );

  const hasStripe = !!org.settings?.stripeSecretKey;
  const hasOpenStripeDispute = invoice.payments.some((p) =>
    paymentHasOpenStripeDispute({
      stripeDisputeId: p.stripeDisputeId,
      disputeStatus: p.disputeStatus,
    })
  );
  const canRefundAllStripe =
    hasStripe &&
    (invoice.status === "PAID" || invoice.status === "PARTIAL") &&
    invoice.payments.some((p) => {
      if (p.method !== "STRIPE" || !p.stripeChargeId) return false;
      const net =
        parseFloat(p.amount.toString()) - parseFloat(String(p.refundedAmount ?? 0));
      return net > 0.001;
    });
  const { chrome, accent } = resolveInvoiceViewChrome(org.settings);

  const settings = org.settings;
  const bankDetails =
    settings?.bankIban || settings?.bankName
      ? {
          bankName: settings.bankName ?? undefined,
          bankIban: settings.bankIban ?? undefined,
          bankBic: settings.bankBic ?? undefined,
          bankAccountHolder: settings.bankAccountHolder ?? undefined,
          bankInstructions: settings.bankInstructions ?? undefined,
        }
      : undefined;

  const taskLineRows = invoice.lines
    .filter((l) => l.taskId && taskById.has(l.taskId))
    .map((l) => ({ line: l, task: taskById.get(l.taskId!)! }));

  const membersForReminders = staffMembers.map((m) => ({
    id: m.user.id,
    name: m.user.name,
    email: m.user.email,
  }));

  const invoiceTab = (
    <div className="space-y-6">
      <PublicInvoiceView
        token=""
        orgName={settings?.companyName ?? org.name}
        orgAddress={settings?.companyAddress ?? undefined}
        orgVat={settings?.companyVat ?? undefined}
        orgLogoUrl={settings?.companyLogoUrl ?? undefined}
        customerName={customerName}
        customerEmail={
          invoice.customer.hideEmailOnInvoice
            ? undefined
            : invoice.customer.email ?? undefined
        }
        customerPhone={
          invoice.customer.hidePhoneOnInvoice
            ? undefined
            : invoice.customer.phone ?? undefined
        }
        customerVat={invoice.customer.vat ?? undefined}
        invoice={{
          id: invoice.id,
          number: invoice.number,
          status: invoice.status,
          currency: invoice.currency,
          issuedAt: invoice.issuedAt.toISOString(),
          dueDate: invoice.dueDate?.toISOString() ?? null,
          subtotal: invoice.subtotal.toString(),
          vat: invoice.vat.toString(),
          total: invoice.total.toString(),
          amountPaid: invoice.amountPaid.toString(),
          amountDue: amountDue.toFixed(2),
          notes: invoice.notes ?? undefined,
          paymentMethod: invoice.paymentMethod,
          template: invoice.template,
          vatIncluded: invoice.vatIncluded,
          vatRate: parseFloat(invoice.vatRate.toString()),
          periodFrom: invoice.periodFrom?.toISOString() ?? null,
          periodTo: invoice.periodTo?.toISOString() ?? null,
          discount: invoice.discount.toString(),
          discountType: invoice.discountType,
          discountValue: invoice.discountValue.toString(),
          discountBeforeTax: invoice.discountBeforeTax,
          termsAndConditions: invoice.termsAndConditions,
        }}
        lines={invoice.lines.map((l) => ({
          name: l.name,
          description: l.description ?? undefined,
          quantity: l.quantity.toString(),
          qtyType: l.qtyType,
          unitPrice: l.unitPrice.toString(),
          total: l.total.toString(),
        }))}
        bankDetails={bankDetails}
        hasStripe={hasStripe}
        accentColor={accent}
        numberFormatStyle={numberFormatStyle}
        showCustomerPayment={false}
        embedded
        useSessionPdf
      />

      <div>
        <h3 className="text-sm font-medium mb-2">Attachments</h3>
        <InvoiceStaffAttachments
          orgSlug={orgSlug}
          attachments={invoice.attachments.map((a) => ({
            id: a.id,
            filename: a.filename,
            sizeBytes: a.sizeBytes,
            uploadedBy: a.uploadedBy,
          }))}
        />
      </div>

      {invoice.payments.length > 0 && (
        <Card className="overflow-hidden border-[var(--border)] shadow-sm">
          <CardHeader
            className="py-4 border-b"
            style={{ backgroundColor: chrome.tableHeaderBg, borderColor: chrome.tableRowBorder }}
          >
            <CardTitle className="text-lg" style={{ color: chrome.headerCellText }}>
              Payments
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table className="text-base">
              <TableHeader>
                <TableRow
                  className="hover:bg-transparent border-b bg-transparent"
                  style={{ borderColor: chrome.tableRowBorder }}
                >
                  <TableHead className="text-[var(--muted-foreground)]">Date</TableHead>
                  <TableHead className="text-[var(--muted-foreground)]">Method</TableHead>
                  <TableHead className="text-[var(--muted-foreground)]">Reference</TableHead>
                  <TableHead className="text-right text-[var(--muted-foreground)]">Applied</TableHead>
                  <TableHead className="text-right text-[var(--muted-foreground)] w-[220px]">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {invoice.payments.map((p, rowIdx) => {
                  const paid = parseFloat(p.amount.toString());
                  const refd = parseFloat(String(p.refundedAmount ?? 0));
                  const net = Math.max(0, paid - refd);
                  return (
                  <TableRow
                    key={p.id}
                    className="border-b"
                    style={{
                      backgroundColor: rowIdx % 2 === 1 ? chrome.tableRowAltBg : undefined,
                      borderColor: chrome.tableRowBorder,
                    }}
                  >
                    <TableCell>{formatDate(p.paidAt)}</TableCell>
                    <TableCell>
                      <Badge variant="secondary">{p.method.replace("_", " ")}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-[var(--muted-foreground)]">
                      {p.stripeChargeId ?? p.notes ?? "-"}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="font-medium text-green-700">
                        {formatCurrency(net.toFixed(2), p.currency, numberFormatStyle)}
                      </div>
                      {refd > 0.001 && (
                        <div className="text-xs text-[var(--muted-foreground)] mt-0.5">
                          Paid {formatCurrency(p.amount.toString(), p.currency, numberFormatStyle)}
                          {" · "}
                          Refunded {formatCurrency(refd.toFixed(2), p.currency, numberFormatStyle)}
                        </div>
                      )}
                      {p.stripeDisputeId && (
                        <div className="text-xs text-amber-700 mt-1">Dispute {p.disputeStatus ?? "open"}</div>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="inline-flex flex-wrap items-center justify-end gap-1">
                        {invoice.status !== "VOID" && (
                          <RefundPaymentMenu
                            orgSlug={orgSlug}
                            paymentId={p.id}
                            method={p.method}
                            stripeChargeId={p.stripeChargeId}
                            amount={p.amount.toString()}
                            refundedAmount={String(p.refundedAmount ?? 0)}
                          />
                        )}
                        <PaymentReceiptActions
                          orgSlug={orgSlug}
                          paymentId={p.id}
                          receiptViewHref={`/${orgSlug}/receipt/${p.id}`}
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {invoice.adminNote && (
        <Card className="border-amber-200 bg-amber-50/50">
          <CardHeader>
            <CardTitle className="text-base text-amber-800">Admin note</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-amber-700 whitespace-pre-wrap">{invoice.adminNote}</p>
          </CardContent>
        </Card>
      )}
    </div>
  );

  const tasksTab =
    taskLineRows.length === 0 ? (
      <p className="text-sm text-[var(--muted-foreground)]">No tasks linked on this invoice&apos;s lines.</p>
    ) : (
      <ul className="space-y-2 text-sm">
        {taskLineRows.map(({ line, task }) => {
          const p = task.project;
          return (
            <li key={line.id} className="rounded-md border border-[var(--border)] p-3">
              <Link
                href={`/${orgSlug}/projects/${p.id}/tasks/${task.id}`}
                className="font-medium text-[var(--primary)] hover:underline"
              >
                {task.title}
              </Link>
              <span className="text-[var(--muted-foreground)]"> · {p.name}</span>
              {line.name && (
                <p className="text-xs text-[var(--muted-foreground)] mt-1">Line: {line.name}</p>
              )}
            </li>
          );
        })}
      </ul>
    );

  const activityTab =
    auditRows.length === 0 ? (
      <p className="text-sm text-[var(--muted-foreground)]">No audit entries for this invoice yet.</p>
    ) : (
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>When</TableHead>
            <TableHead>Action</TableHead>
            <TableHead>Actor</TableHead>
            <TableHead>Details</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {auditRows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="whitespace-nowrap text-sm">{formatDateTime(row.createdAt)}</TableCell>
              <TableCell className="text-sm">{row.action}</TableCell>
              <TableCell className="text-sm text-[var(--muted-foreground)]">
                {row.actorEmail ?? row.userId ?? "-"}
              </TableCell>
              <TableCell className="text-xs text-[var(--muted-foreground)] font-mono max-w-md truncate">
                {row.metadata ? JSON.stringify(row.metadata) : "-"}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );

  const remindersTab = (
    <InvoiceRemindersPanel
      orgSlug={orgSlug}
      invoiceId={invoiceId}
      reminders={invoiceReminders.map((r) => ({
        id: r.id,
        title: r.title,
        description: r.description,
        notifyAt: r.notifyAt,
        channels: r.channels,
        sent: r.sent,
      }))}
      members={membersForReminders}
    />
  );

  const notesTab = (
    <InvoiceNotesPanel
      orgSlug={orgSlug}
      invoiceId={invoiceId}
      notes={invoice.staffNotes.map((n) => ({
        id: n.id,
        content: n.content,
        createdAt: n.createdAt,
        authorId: n.authorId,
      }))}
    />
  );

  const emailsTab = (
    <div className="space-y-4">
      <p className="text-xs text-[var(--muted-foreground)]">
        Opens are recorded when a tracking pixel loads in the email client (when enabled in organization settings). This is not proof the message was read.
      </p>
      {emailSends.length === 0 ? (
        <p className="text-sm text-[var(--muted-foreground)]">No tracked sends yet.</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Sent</TableHead>
              <TableHead>To</TableHead>
              <TableHead>Kind</TableHead>
              <TableHead>Subject</TableHead>
              <TableHead>Opens</TableHead>
              <TableHead>First / last open</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {emailSends.map((s) => {
              const times = s.opens.map((o) => o.openedAt.getTime());
              const first = times.length ? new Date(Math.min(...times)) : null;
              const last = times.length ? new Date(Math.max(...times)) : null;
              return (
                <TableRow key={s.id}>
                  <TableCell className="whitespace-nowrap text-sm">{formatDateTime(s.sentAt)}</TableCell>
                  <TableCell className="text-sm">{s.toEmail}</TableCell>
                  <TableCell className="text-sm">{s.kind}</TableCell>
                  <TableCell className="text-sm max-w-[200px] truncate">{s.subject ?? "-"}</TableCell>
                  <TableCell className="text-sm">{s.opens.length}</TableCell>
                  <TableCell className="text-xs text-[var(--muted-foreground)]">
                    {first && last ? (
                      <>
                        {formatDateTime(first)}
                        {first.getTime() !== last.getTime() && <> - {formatDateTime(last)}</>}
                      </>
                    ) : (
                      "-"
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </div>
  );

  const viewsTab =
    publicViews.length === 0 ? (
      <p className="text-sm text-[var(--muted-foreground)]">No views of the public invoice link yet.</p>
    ) : (
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Viewed</TableHead>
            <TableHead>IP</TableHead>
            <TableHead>User-Agent</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {publicViews.map((v) => (
            <TableRow key={v.id}>
              <TableCell className="whitespace-nowrap text-sm">{formatDateTime(v.viewedAt)}</TableCell>
              <TableCell className="text-sm font-mono">{v.ipAddress ?? "-"}</TableCell>
              <TableCell className="text-xs text-[var(--muted-foreground)] max-w-md truncate">
                {v.userAgent ?? "-"}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6 px-4 sm:px-6">
      {paymentStatus === "success" && (
        <div className="rounded-md bg-green-50 border border-green-200 p-4 text-green-800 text-sm font-medium">
          Payment received successfully! The invoice has been updated.
        </div>
      )}

      <div
        className="flex items-center gap-4 rounded-lg border px-5 py-4 sm:px-6"
        style={{
          backgroundColor: chrome.datesBg,
          borderColor: chrome.datesBorder,
        }}
      >
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/invoices`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-3xl font-bold font-mono tracking-tight" style={{ color: chrome.headerCellText }}>
              {invoice.number}
            </h1>
            <Badge variant={STATUS_VARIANTS[invoice.status]}>{invoice.status}</Badge>
            {hasOpenStripeDispute && (
              <Badge variant="warning" title="Stripe dispute in progress - invoice stays paid until the dispute is lost.">
                Dispute
              </Badge>
            )}
          </div>
          <p className="text-base mt-1" style={{ color: chrome.headerCellText, opacity: 0.85 }}>
            <Link href={`/${orgSlug}/customers/${invoice.customer.id}`} className="hover:underline font-medium">
              {customerName}
            </Link>
            {" · "}
            {formatDate(invoice.issuedAt)}
          </p>
        </div>
        {invoice.status === "DRAFT" && (
          <Button variant="outline" size="sm" asChild>
            <Link href={`/${orgSlug}/invoices/${invoiceId}/edit`}>
              <Pencil className="h-4 w-4" />
              Edit
            </Link>
          </Button>
        )}
      </div>

      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between lg:gap-4">
        <div className="min-w-0 flex-1 flex flex-wrap items-center gap-2">
          <InvoiceActions
            invoiceId={invoiceId}
            orgSlug={orgSlug}
            status={invoice.status}
            currency={invoice.currency}
            amountDue={amountDue}
            hasStripe={hasStripe}
            invoiceEmailRecipients={invoiceEmailRecipients}
            onSend={async (opts) => {
              "use server";
              return sendInvoiceEmail(orgSlug, invoiceId, opts);
            }}
            onVoid={async () => {
              "use server";
              return voidInvoice(orgSlug, invoiceId);
            }}
            onRecordPayment={async (data) => {
              "use server";
              return recordManualPayment(orgSlug, data);
            }}
            onStripeCheckout={async () => {
              "use server";
              return createStripeCheckoutSession(orgSlug, invoiceId);
            }}
            onGetPreviewUrl={async () => {
              "use server";
              return getInvoicePreviewUrl(orgSlug, invoiceId);
            }}
            onDuplicateAsDraft={async () => {
              "use server";
              return duplicateInvoiceAsDraft(orgSlug, invoiceId);
            }}
          />
          {canRefundAllStripe && (
            <InvoiceRefundAllStripeButton orgSlug={orgSlug} invoiceId={invoiceId} />
          )}
        </div>
        {canDeleteInvoiceWithVerification && (
          <div className="shrink-0 lg:pt-0.5">
            <InvoiceDeleteVerifiedZone
              orgSlug={orgSlug}
              invoiceId={invoiceId}
              invoiceNumber={invoice.number}
              smtpConfigured={invoiceDeletionSmtpReady}
            />
          </div>
        )}
      </div>

      <Suspense fallback={<div className="h-32 animate-pulse rounded-lg bg-[var(--muted)]/30" />}>
        <InvoiceDetailShell
          orgSlug={orgSlug}
          invoiceId={invoiceId}
          defaultTab={tabParam}
          invoicePanel={invoiceTab}
          tasksPanel={tasksTab}
          activityPanel={activityTab}
          remindersPanel={remindersTab}
          notesPanel={notesTab}
          emailsPanel={emailsTab}
          viewsPanel={viewsTab}
        />
      </Suspense>
    </div>
  );
}
