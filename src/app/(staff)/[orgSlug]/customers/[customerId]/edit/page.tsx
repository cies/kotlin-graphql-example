import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import { CustomerForm } from "@/components/customers/customer-form";

interface Props {
  params: Promise<{ orgSlug: string; customerId: string }>;
}

export const metadata = { title: "Edit Customer" };

export default async function EditCustomerPage({ params }: Props) {
  const { orgSlug, customerId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const customer = await prisma.customer.findUnique({
    where: { id: customerId, organizationId: org.id },
    include: { notificationEmails: { select: { purpose: true, email: true } } },
  });

  if (!customer) notFound();

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Edit Customer</h1>
      </div>
      <CustomerForm
        orgSlug={orgSlug}
        customerId={customerId}
        defaultValues={{
          type: customer.type,
          companyName: customer.companyName ?? "",
          firstName: customer.firstName ?? "",
          lastName: customer.lastName ?? "",
          email: customer.email ?? "",
          phone: customer.phone ?? "",
          vat: customer.vat ?? "",
          preferredCurrency: customer.preferredCurrency,
          notes: customer.notes ?? "",
          billingAddress: (customer.billingAddress as Record<string, string>) ?? {},
          shippingAddress: (customer.shippingAddress as Record<string, string>) ?? {},
          hideEmailOnInvoice: customer.hideEmailOnInvoice,
          hidePhoneOnInvoice: customer.hidePhoneOnInvoice,
          notificationEmails: customer.notificationEmails,
        }}
      />
    </div>
  );
}
