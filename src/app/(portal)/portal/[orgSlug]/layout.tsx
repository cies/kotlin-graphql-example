import { auth } from "@/lib/auth/auth";
import { signOut } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Building2 } from "lucide-react";
import { Button } from "@/components/ui/button";

interface Props {
  children: React.ReactNode;
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalLayout({ children, params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();

  if (!session?.user) {
    redirect(`/portal/${orgSlug}/login`);
  }

  if (session.user.userType !== "CUSTOMER_CONTACT") {
    redirect(`/${orgSlug}`);
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true, settings: { select: { companyLogoUrl: true } } },
  });

  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: {
      canSeeProjects: true,
      canSeeTasks: true,
      canSeeInvoices: true,
      canSeeContracts: true,
    },
  });
  if (!contact) redirect("/auth/login");

  return (
    <div className="min-h-screen bg-[var(--muted)]">
      <header className="bg-[var(--background)] border-b border-[var(--border)] px-6 py-4">
        <div className="max-w-5xl mx-auto flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            {org.settings?.companyLogoUrl ? (
              <img src={org.settings.companyLogoUrl} alt={org.name} className="h-8 w-auto" />
            ) : (
              <div className="flex items-center gap-2">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-[var(--primary)] text-white">
                  <Building2 className="h-4 w-4" />
                </div>
                <span className="font-semibold">{org.name}</span>
              </div>
            )}
            <span className="text-[var(--muted-foreground)] text-sm ml-2">Client Portal</span>
          </div>
          <form
            action={async () => {
              "use server";
              await signOut({ redirectTo: `/portal/${orgSlug}/login` });
            }}
          >
            <Button variant="outline" size="sm">Sign out</Button>
          </form>
        </div>
      </header>
      <div className="bg-[var(--background)] border-b border-[var(--border)]">
        <nav className="max-w-5xl mx-auto px-6 py-3 flex flex-wrap gap-4 text-sm">
          <Link href={`/portal/${orgSlug}`} className="hover:underline">Dashboard</Link>
          {contact.canSeeProjects && <Link href={`/portal/${orgSlug}/projects`} className="hover:underline">Projects</Link>}
          {contact.canSeeTasks && <Link href={`/portal/${orgSlug}/tasks`} className="hover:underline">Tasks</Link>}
          {contact.canSeeInvoices && (
            <>
              <Link href={`/portal/${orgSlug}/invoices`} className="hover:underline">Invoices</Link>
              <Link href={`/portal/${orgSlug}/receipts`} className="hover:underline">Receipts</Link>
              <Link href={`/portal/${orgSlug}/account-statement`} className="hover:underline">Statement</Link>
            </>
          )}
          {contact.canSeeContracts && <Link href={`/portal/${orgSlug}/contracts`} className="hover:underline">Contracts</Link>}
        </nav>
      </div>
      <main className="max-w-5xl mx-auto p-6">{children}</main>
    </div>
  );
}
