import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Sidebar } from "@/components/layout/sidebar";
import { Header } from "@/components/layout/header";
import { isPlatformAdminEmail } from "@/lib/platform-admin";

interface Props {
  children: React.ReactNode;
  params: Promise<{ orgSlug: string }>;
}

export default async function StaffLayout({ children, params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();

  if (!session?.user) {
    redirect(`/auth/login?callbackUrl=/${orgSlug}`);
  }

  if (session.user.userType !== "STAFF") {
    redirect(`/portal/${orgSlug}`);
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true, slug: true },
  });

  if (!org) {
    redirect("/auth/login");
  }

  const membership = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: {
        organizationId: org.id,
        userId: session.user.id,
      },
    },
  });

  if (!membership) {
    redirect("/auth/login");
  }

  const showPlatformAdminLink =
    typeof session.user.email === "string" && isPlatformAdminEmail(session.user.email);

  /* Fixed shell: keeps the app out of normal document flow so only <main> scrolls - avoids double body + main scrollbars. */
  return (
    <div className="fixed inset-0 z-0 flex min-h-0 overflow-hidden bg-[var(--background)]">
      <Sidebar orgSlug={orgSlug} orgName={org.name} showPlatformAdminLink={showPlatformAdminLink} />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <Header />
        <main className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden overscroll-y-contain bg-[var(--background)] p-6">
          {children}
        </main>
      </div>
    </div>
  );
}
