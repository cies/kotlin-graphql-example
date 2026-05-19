import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { notFound, redirect } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import { EditContactForm } from "./edit-contact-form";

interface Props {
  params: Promise<{ orgSlug: string; customerId: string; contactId: string }>;
}

export default async function EditContactPage({ params }: Props) {
  const { orgSlug, customerId, contactId } = await params;

  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findUnique({
    where: { id: contactId },
    include: { user: { select: { name: true, email: true } } },
  });

  if (
    !contact ||
    contact.organizationId !== org.id ||
    contact.customerId !== customerId
  ) {
    notFound();
  }

  return (
    <div className="max-w-lg">
      <div className="flex items-center gap-3 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/customers/${customerId}`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Edit Contact</h1>
          <p className="text-[var(--muted-foreground)] text-sm font-mono mt-0.5">
            {contact.user.email}
          </p>
        </div>
      </div>

      <EditContactForm
        orgSlug={orgSlug}
        customerId={customerId}
        contactId={contactId}
        initialData={{
          name: contact.user.name ?? "",
          email: contact.user.email ?? "",
          isPrimary: contact.isPrimary,
          canSeeProjects: contact.canSeeProjects,
          canSeeTasks: contact.canSeeTasks,
          canSeeInvoices: contact.canSeeInvoices,
          canSeeContracts: contact.canSeeContracts,
          canPayInvoices: contact.canPayInvoices,
        }}
      />
    </div>
  );
}
