import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { ArrowLeft } from "lucide-react";
import { RecurringInvoiceTemplateForm } from "@/components/invoices/recurring-invoice-template-form";
import { updateRecurringRuleTemplate } from "@/lib/actions/invoices";
import {
  recurringRuleTemplateDataSchema,
  type RecurringRuleTemplateDataInput,
} from "@/lib/invoices/recurring-template-schema";
import type { Currency, InvoiceTemplate } from "@prisma/client";
import { normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string; ruleId: string }>;
}

export const metadata = { title: "Edit recurring template" };

function fallbackTemplate(
  org: {
    settings: {
      vatRate?: unknown;
      defaultVatIncluded?: boolean | null;
      defaultInvoiceTemplate?: string | null;
      defaultDueDays?: number | null;
    } | null;
  },
  customerCurrency: Currency
): RecurringRuleTemplateDataInput {
  return recurringRuleTemplateDataSchema.parse({
    currency: customerCurrency,
    vatRate: org.settings?.vatRate != null ? org.settings.vatRate.toString() : undefined,
    vatIncluded: org.settings?.defaultVatIncluded ?? false,
    template: (org.settings?.defaultInvoiceTemplate as InvoiceTemplate | undefined) ?? "CLASSIC",
    daysUntilDue: org.settings?.defaultDueDays ?? 30,
    lines: [{ name: "Item", quantity: "1", unitPrice: "0", qtyType: "QTY" }],
  });
}

export default async function EditRecurringTemplatePage({ params }: Props) {
  const { orgSlug, ruleId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  const rule = await prisma.recurringInvoiceRule.findFirst({
    where: { id: ruleId, organizationId: org.id },
  });
  if (!rule) redirect(`/${orgSlug}/invoices/recurring`);

  const customer = await prisma.customer.findFirst({
    where: { id: rule.customerId, organizationId: org.id },
    select: {
      type: true,
      companyName: true,
      firstName: true,
      lastName: true,
      preferredCurrency: true,
    },
  });
  const customerLabel =
    customer?.type === "B2B"
      ? customer.companyName ?? "Customer"
      : `${customer?.firstName ?? ""} ${customer?.lastName ?? ""}`.trim() || "Customer";

  const parsed = recurringRuleTemplateDataSchema.safeParse(rule.templateData);
  const initialTemplate = parsed.success
    ? parsed.data
    : fallbackTemplate(org, customer?.preferredCurrency ?? "EUR");

  const orgVatRate = org.settings?.vatRate?.toString() ?? "0";
  const orgDefaultVatIncluded = org.settings?.defaultVatIncluded ?? false;
  const orgDefaultTemplate = org.settings?.defaultInvoiceTemplate ?? "CLASSIC";

  async function handleSave(data: RecurringRuleTemplateDataInput) {
    "use server";
    return updateRecurringRuleTemplate(orgSlug, ruleId, data);
  }

  return (
    <div>
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/invoices/recurring`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Recurring invoice template</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            Future scheduled invoices use this template. Past invoices are unchanged.
          </p>
        </div>
      </div>

      <RecurringInvoiceTemplateForm
        orgSlug={orgSlug}
        ruleId={ruleId}
        ruleInterval={rule.interval}
        nextRunAt={rule.nextRunAt.toISOString()}
        customerLabel={customerLabel}
        initialTemplate={initialTemplate}
        orgVatRate={orgVatRate}
        orgDefaultVatIncluded={orgDefaultVatIncluded}
        orgDefaultTemplate={orgDefaultTemplate}
        numberFormatStyle={normalizeNumberFormatStyle(
          (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
        )}
        onSubmit={handleSave}
        successHref={`/${orgSlug}/invoices/recurring`}
      />
    </div>
  );
}
