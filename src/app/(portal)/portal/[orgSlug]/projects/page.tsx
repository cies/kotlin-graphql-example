import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalProjectsPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug }, select: { id: true } });
  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: { customerId: true, canSeeProjects: true, canSeeTasks: true, projectIds: true },
  });
  if (!contact || !contact.canSeeProjects) redirect(`/portal/${orgSlug}`);

  const projects = await prisma.project.findMany({
    where: {
      organizationId: org.id,
      customerId: contact.customerId,
      ...(contact.projectIds.length ? { id: { in: contact.projectIds } } : {}),
    },
    include: {
      tasks: contact.canSeeTasks
        ? { select: { id: true, title: true, status: true, dueDate: true }, orderBy: { createdAt: "desc" }, take: 8 }
        : false,
    },
    orderBy: { createdAt: "desc" },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Projects</h1>
      {projects.length === 0 && <p className="text-sm text-[var(--muted-foreground)]">No projects available.</p>}
      {projects.map((project) => (
        <Card key={project.id}>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>{project.name}</span>
              <Badge variant="outline">{project.status}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {project.description && <p className="text-sm text-[var(--muted-foreground)]">{project.description}</p>}
            {contact.canSeeTasks && Array.isArray(project.tasks) && (
              <div className="space-y-1">
                <p className="text-sm font-medium">Recent tasks</p>
                {project.tasks.length === 0 ? (
                  <p className="text-sm text-[var(--muted-foreground)]">No tasks.</p>
                ) : (
                  project.tasks.map((task) => (
                    <div key={task.id} className="flex items-center justify-between text-sm rounded-md border border-[var(--border)] p-2">
                      <span>{task.title}</span>
                      <Badge variant="secondary">{task.status}</Badge>
                    </div>
                  ))
                )}
              </div>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
