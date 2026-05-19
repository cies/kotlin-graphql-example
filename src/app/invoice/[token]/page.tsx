import { notFound } from "next/navigation";
import { prisma } from "@/lib/db/prisma";
import { PublicInvoiceView } from "@/components/invoices/public-invoice-view";
import { normalizeNumberFormatStyle } from "@/lib/utils/format";
import { logInvoicePublicView } from "@/lib/invoice/log-public-view";
import { resolveInvoiceViewChrome } from "@/lib/invoice/invoice-view-chrome";

interface Props {
  params: Promise<{ token: string }>;
  searchParams: Promise<{ payment?: string }>;
}

export default async function PublicInvoicePage({ params, searchParams }: Props) {
  const { token } = await params;
  const { payment } = await searchParams;

  const invoice = await prisma.invoice.findUnique({
    where: { viewToken: token },
    include: {
      customer: true,
      lines: { orderBy: { sortOrder: "asc" } },
      organization: {
        include: { settings: true },
      },
      payments: { orderBy: { paidAt: "desc" } },
      attachments: { orderBy: { createdAt: "desc" } },
    },
  });

  if (!invoice || invoice.status === "DRAFT" || !invoice.viewToken) {
    notFound();
  }

  await logInvoicePublicView(invoice.id);

  const org = invoice.organization;
  const settings = org.settings;
  const customer = invoice.customer;

  const customerName =
    customer.companyName ??
    [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
    customer.email ??
    "Customer";

  const amountDue =
    parseFloat(invoice.total.toString()) - parseFloat(invoice.amountPaid.toString());

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

  const { accent } = resolveInvoiceViewChrome(settings);

  return (
    <PublicInvoiceView
      token={token}
      orgName={settings?.companyName ?? org.name}
      orgAddress={settings?.companyAddress ?? undefined}
      orgVat={settings?.companyVat ?? undefined}
      orgLogoUrl={settings?.companyLogoUrl ?? undefined}
      customerName={customerName}
      customerEmail={
        customer.hideEmailOnInvoice ? undefined : customer.email ?? undefined
      }
      customerPhone={
        customer.hidePhoneOnInvoice ? undefined : customer.phone ?? undefined
      }
      customerVat={customer.vat ?? undefined}
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
      hasStripe={!!settings?.stripeSecretKey}
      paymentStatus={payment as "success" | "cancelled" | undefined}
      numberFormatStyle={normalizeNumberFormatStyle(
        (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
      )}
      accentColor={accent}
      publicAttachments={invoice.attachments.map((a) => ({
        id: a.id,
        filename: a.filename,
        sizeBytes: a.sizeBytes,
      }))}
    />
  );
}
