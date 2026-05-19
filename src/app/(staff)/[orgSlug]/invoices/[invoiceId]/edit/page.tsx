import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { InvoiceForm, type InvoiceFormValues } from "@/components/invoices/invoice-form";
import { updateInvoice } from "@/lib/actions/invoices";
import type { Currency } from "@prisma/client";
import { normalizeNumberFormatStyle } from "@/lib/utils/format";
import { recurringRuleTemplateDataSchema } from "@/lib/invoices/recurring-template-schema";
import Link from "next/link";

interface Props {
  params: Promise<{ orgSlug: string; invoiceId: string }>;
}

export const metadata = { title: "Edit Invoice" };

export default async function EditInvoicePage({ params }: Props) {
  const { orgSlug, invoiceId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: org.id },
    include: { lines: { orderBy: { sortOrder: "asc" } } },
  });

  if (!invoice) redirect(`/${orgSlug}/invoices`);
  if (invoice.status !== "DRAFT") redirect(`/${orgSlug}/invoices/${invoiceId}`);

  const recurringRule =
    invoice.recurringRuleId != null
      ? await prisma.recurringInvoiceRule.findFirst({
          where: { id: invoice.recurringRuleId, organizationId: org.id },
        })
      : null;

  const recurringTemplateParsed =
    recurringRule != null
      ? recurringRuleTemplateDataSchema.safeParse(recurringRule.templateData)
      : null;

  const recurringFormDefaults =
    recurringRule &&
    ["WEEKLY", "BIWEEKLY", "MONTHLY"].includes(recurringRule.interval)
      ? {
          recurring: true as const,
          recurringInterval: recurringRule.interval as "WEEKLY" | "BIWEEKLY" | "MONTHLY",
          recurringStartDate: recurringRule.nextRunAt.toISOString().split("T")[0],
          recurringEndDate: recurringRule.endAt
            ? recurringRule.endAt.toISOString().split("T")[0]
            : undefined,
          recurringBilledPeriodMode:
            recurringRule.interval === "MONTHLY" && recurringTemplateParsed?.success
              ? recurringTemplateParsed.data.billedPeriodMode
              : ("NONE" as const),
        }
      : {};

  const customers = await prisma.customer.findMany({
    where: { organizationId: org.id },
    orderBy: { createdAt: "desc" },
    select: {
      id: true,
      type: true,
      companyName: true,
      firstName: true,
      lastName: true,
      preferredCurrency: true,
      email: true,
      vat: true,
      billingAddress: true,
    },
  });

  const customerOptions = customers.map((c) => ({
    id: c.id,
    label:
      c.type === "B2B"
        ? c.companyName ?? "Unnamed"
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim() || "Unnamed",
    preferredCurrency: c.preferredCurrency,
    email: c.email,
    vat: c.vat,
    billingAddress: c.billingAddress,
  }));

  const orgVatRate = org.settings?.vatRate?.toString() ?? "0";
  const orgDefaultTemplate = org.settings?.defaultInvoiceTemplate ?? "CLASSIC";
  const orgDefaultVatIncluded = org.settings?.defaultVatIncluded ?? false;
  const orgDefaultDueDays = org.settings?.defaultDueDays ?? null;

  async function handleSubmit(data: InvoiceFormValues) {
    "use server";
    const result = await updateInvoice(orgSlug, invoiceId, data as Parameters<typeof updateInvoice>[2]);
    if (result.error) throw new Error(result.error);
    redirect(`/${orgSlug}/invoices/${invoiceId}`);
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Edit Invoice {invoice.number}</h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">
          Only draft invoices can be edited
        </p>
      </div>

      {invoice.recurringRuleId && (
        <div className="mb-6 rounded-md border border-[var(--border)] bg-[var(--muted)]/30 px-4 py-3 text-sm">
          <p className="text-[var(--foreground)]">
            This draft is linked to a recurring schedule. Line items and amounts on this invoice do not
            change future runs. Edit the{" "}
            <Link
              href={`/${orgSlug}/invoices/recurring/${invoice.recurringRuleId}/edit`}
              className="font-medium text-[var(--primary)] underline underline-offset-2"
            >
              recurring template
            </Link>{" "}
            to update what gets generated next.
          </p>
        </div>
      )}

      <InvoiceForm
        customers={customerOptions}
        orgVatRate={orgVatRate}
        orgDefaultVatIncluded={orgDefaultVatIncluded}
        orgDefaultDueDays={orgDefaultDueDays}
        defaultCurrency={invoice.currency as Currency}
        orgDefaultTemplate={orgDefaultTemplate}
        numberFormatStyle={normalizeNumberFormatStyle(
          (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
        )}
        onSubmit={handleSubmit}
        submitLabel="Save Changes"
        defaultValues={{
          customerId: invoice.customerId,
          currency: invoice.currency as Currency,
          dueDate: invoice.dueDate?.toISOString().split("T")[0],
          issuedAt: invoice.issuedAt.toISOString().split("T")[0],
          vatIncluded: invoice.vatIncluded,
          periodFrom: invoice.periodFrom?.toISOString().split("T")[0],
          periodTo: invoice.periodTo?.toISOString().split("T")[0],
          notes: invoice.notes ?? "",
          adminNote: invoice.adminNote ?? "",
          termsAndConditions: invoice.termsAndConditions ?? "",
          paymentMethod: invoice.paymentMethod ?? "",
          template: invoice.template ?? orgDefaultTemplate,
          discountType: (invoice.discountType ?? "NONE") as "NONE" | "PERCENTAGE" | "FIXED",
          discountValue: invoice.discountValue?.toString() ?? "",
          discountBeforeTax: invoice.discountBeforeTax,
          lines: invoice.lines.map((l) => ({
            name: l.name,
            description: l.description ?? "",
            quantity: l.quantity.toString(),
            qtyType: (l.qtyType ?? "QTY") as "QTY" | "HOURS" | "QTY_HOURS",
            unitPrice: l.unitPrice.toString(),
            sortOrder: l.sortOrder,
            taskId: l.taskId ?? undefined,
          })),
          ...recurringFormDefaults,
        }}
        orgSlug={orgSlug}
        invoiceId={invoiceId}
        invoiceNumber={invoice.number}
      />
    </div>
  );
}
