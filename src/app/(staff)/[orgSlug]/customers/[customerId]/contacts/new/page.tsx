import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { redirect } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import { NewContactForm } from "./new-contact-form";

interface Props {
  params: Promise<{ orgSlug: string; customerId: string }>;
}

export default async function NewContactPage({ params }: Props) {
  const { orgSlug, customerId } = await params;

  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const [customer, member] = await Promise.all([
    prisma.customer.findUnique({
      where: { id: customerId, organizationId: org.id },
      select: { type: true, _count: { select: { contacts: true } } },
    }),
    prisma.organizationMember.findUnique({
      where: {
        organizationId_userId: { organizationId: org.id, userId: session.user.id },
      },
      select: { role: true },
    }),
  ]);

  if (!customer || !member) redirect(`/${orgSlug}/customers`);

  const isAdmin = member.role === "OWNER" || member.role === "ADMIN";

  // B2C: only OWNER/ADMIN can add a second contact
  if (customer.type === "B2C" && customer._count.contacts >= 1 && !isAdmin) {
    redirect(`/${orgSlug}/customers/${customerId}`);
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
          <h1 className="text-2xl font-bold">Add Contact</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            Add a portal user or an email-only contact for invoices (no password).
          </p>
        </div>
      </div>

      <NewContactForm orgSlug={orgSlug} customerId={customerId} />
    </div>
  );
}
