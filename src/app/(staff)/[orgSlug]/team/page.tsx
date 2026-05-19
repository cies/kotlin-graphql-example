import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { MemberList } from "@/components/team/member-list";
import { UserPlus } from "lucide-react";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function TeamPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const [members, invites] = await Promise.all([
    prisma.organizationMember.findMany({
      where: { organizationId: org.id },
      include: { user: { select: { id: true, name: true, email: true, image: true } } },
      orderBy: { createdAt: "asc" },
    }),
    prisma.memberInvite.findMany({
      where: { organizationId: org.id, acceptedAt: null, expiresAt: { gt: new Date() } },
      orderBy: { createdAt: "desc" },
    }),
  ]);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Team</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
            Manage members, roles, and billing rates
          </p>
        </div>
        <Button asChild>
          <Link href={`/${orgSlug}/team/invite`}>
            <UserPlus className="h-4 w-4" />
            Invite member
          </Link>
        </Button>
      </div>

      <MemberList
        orgSlug={orgSlug}
        members={members}
        invites={invites}
        currentUserId={session.user.id}
      />
    </div>
  );
}
