import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ArrowLeft } from "lucide-react";

interface Props {
  params: Promise<{ orgSlug: string; projectId: string }>;
}

export const metadata = { title: "Customer preview - Project" };

export default async function ProjectCustomerPreviewPage({ params }: Props) {
  const { orgSlug, projectId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
  });
  if (!member) redirect("/auth/login");

  const project = await prisma.project.findUnique({
    where: { id: projectId, organizationId: org.id },
    include: {
      tasks: {
        select: { id: true, title: true, status: true, dueDate: true },
        orderBy: { createdAt: "desc" },
        take: 8,
      },
    },
  });
  if (!project) notFound();

  return (
    <div className="space-y-4">
      <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
        Staff preview - this is not the customer portal. Contacts sign in at{" "}
        <span className="font-medium">/portal/{orgSlug}</span>.
      </div>

      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/projects/${projectId}`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <h1 className="text-2xl font-bold">Customer view preview</h1>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between gap-2 flex-wrap">
            <span>{project.name}</span>
            <Badge variant="outline">{project.status}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {project.description && (
            <p className="text-sm text-[var(--muted-foreground)]">{project.description}</p>
          )}
          <div className="space-y-1">
            <p className="text-sm font-medium">Recent tasks</p>
            {project.tasks.length === 0 ? (
              <p className="text-sm text-[var(--muted-foreground)]">No tasks.</p>
            ) : (
              project.tasks.map((task) => (
                <div
                  key={task.id}
                  className="flex items-center justify-between text-sm rounded-md border border-[var(--border)] p-2"
                >
                  <span>{task.title}</span>
                  <Badge variant="secondary">{task.status}</Badge>
                </div>
              ))
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
