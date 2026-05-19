import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDate } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalTasksPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug }, select: { id: true } });
  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: { customerId: true, canSeeTasks: true, projectIds: true },
  });
  if (!contact || !contact.canSeeTasks) redirect(`/portal/${orgSlug}`);

  const tasks = await prisma.task.findMany({
    where: {
      organizationId: org.id,
      project: {
        customerId: contact.customerId,
        ...(contact.projectIds.length ? { id: { in: contact.projectIds } } : {}),
      },
    },
    include: { project: { select: { name: true } } },
    orderBy: { createdAt: "desc" },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Tasks</h1>
      <Card>
        <CardHeader>
          <CardTitle>Task list</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {tasks.length === 0 && <p className="text-sm text-[var(--muted-foreground)]">No tasks available.</p>}
          {tasks.map((task) => (
            <div key={task.id} className="rounded-md border border-[var(--border)] p-3 flex items-center justify-between gap-3">
              <div>
                <p className="font-medium">{task.title}</p>
                <p className="text-sm text-[var(--muted-foreground)]">{task.project.name}</p>
                {task.dueDate && <p className="text-xs text-[var(--muted-foreground)]">Due {formatDate(task.dueDate)}</p>}
              </div>
              <Badge variant={task.status === "DONE" ? "success" : task.status === "IN_PROGRESS" ? "info" : "secondary"}>
                {task.status}
              </Badge>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
