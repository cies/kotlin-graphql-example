import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { InvoiceForm, type InvoiceFormValues } from "@/components/invoices/invoice-form";
import { createInvoice } from "@/lib/actions/invoices";
import type { Currency } from "@prisma/client";
import { normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<{ customerId?: string; projectId?: string }>;
}

export const metadata = { title: "New Invoice" };

export default async function NewInvoicePage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const { customerId: preselectedCustomerId, projectId: preselectedProjectId } = await searchParams;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  let invoiceProjectId: string | undefined;
  if (preselectedCustomerId && preselectedProjectId) {
    const proj = await prisma.project.findFirst({
      where: {
        id: preselectedProjectId,
        organizationId: org.id,
        customerId: preselectedCustomerId,
      },
      select: { id: true },
    });
    if (proj) invoiceProjectId = proj.id;
  }

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
  const orgCurrency = (org.settings?.displayCurrency ?? "EUR") as Currency;
  const orgDefaultTemplate = org.settings?.defaultInvoiceTemplate ?? "CLASSIC";
  const orgDefaultVatIncluded = org.settings?.defaultVatIncluded ?? false;
  const orgDefaultDueDays = org.settings?.defaultDueDays ?? null;

  async function handleSubmit(data: InvoiceFormValues) {
    "use server";
    const result = await createInvoice(orgSlug, data as Parameters<typeof createInvoice>[1]);
    if (result.error) throw new Error(result.error);
    redirect(`/${orgSlug}/invoices/${result.invoiceId}`);
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">New Invoice</h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">
          Create a new invoice for a customer
        </p>
      </div>

      <InvoiceForm
        customers={customerOptions}
        orgVatRate={orgVatRate}
        orgDefaultVatIncluded={orgDefaultVatIncluded}
        orgDefaultDueDays={orgDefaultDueDays}
        defaultCurrency={orgCurrency}
        orgDefaultTemplate={orgDefaultTemplate}
        orgInvoiceNumbering={
          org.settings
            ? {
                invoiceNumberPrefix: org.settings.invoiceNumberPrefix,
                invoiceNumberFormat: org.settings.invoiceNumberFormat,
                invoiceNumberPadding: org.settings.invoiceNumberPadding,
                invoiceNumberRandomLength: org.settings.invoiceNumberRandomLength,
              }
            : null
        }
        numberFormatStyle={normalizeNumberFormatStyle(
          (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
        )}
        onSubmit={handleSubmit}
        submitLabel="Create Invoice"
        defaultValues={
          preselectedCustomerId
            ? { customerId: preselectedCustomerId }
            : undefined
        }
        projectId={invoiceProjectId}
        orgSlug={orgSlug}
      />
    </div>
  );
}
