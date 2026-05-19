import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { TaskStatus } from "@prisma/client";
import { TaskDescription } from "@/components/tasks/task-description";
import { TaskComments } from "@/components/tasks/task-comments";
import { TaskAttachments } from "@/components/tasks/task-attachments";
import { formatDate } from "@/lib/utils/format";

const STATUS_VARIANT: Record<
  TaskStatus,
  "default" | "secondary" | "destructive" | "outline" | "success" | "warning" | "info"
> = {
  TODO: "secondary",
  IN_PROGRESS: "info",
  REVIEW: "warning",
  DONE: "success",
  CANCELLED: "outline",
};

interface Props {
  params: Promise<{ orgSlug: string; taskId: string }>;
}

export default async function PortalTaskDetailPage({ params }: Props) {
  const { orgSlug, taskId } = await params;

  const session = await auth();
  if (!session?.user || session.user.userType !== "CUSTOMER_CONTACT") {
    redirect(`/portal/${orgSlug}/login`);
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) notFound();

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id, canSeeTasks: true },
    select: { customerId: true, projectIds: true },
  });
  if (!contact) notFound();

  const task = await prisma.task.findUnique({
    where: { id: taskId, organizationId: org.id },
    include: {
      project: { select: { id: true, name: true, customerId: true } },
      comments: {
        orderBy: { createdAt: "asc" },
        include: { author: { select: { id: true, name: true, email: true } } },
      },
      attachments: {
        orderBy: { createdAt: "asc" },
        include: { uploader: { select: { name: true, email: true } } },
      },
    },
  });

  if (!task) notFound();

  // Verify the customer owns the project or has explicit access
  const projectAllowed =
    task.project.customerId === contact.customerId ||
    contact.projectIds.includes(task.project.id);
  if (!projectAllowed) notFound();

  const comments = task.comments.map((c) => ({
    id: c.id,
    body: c.body,
    createdAt: c.createdAt.toISOString(),
    author: { id: c.author.id, name: c.author.name, email: c.author.email },
  }));

  const attachments = task.attachments.map((a) => ({
    id: a.id,
    filename: a.filename,
    mimeType: a.mimeType,
    sizeBytes: a.sizeBytes,
    createdAt: a.createdAt.toISOString(),
    uploader: { name: a.uploader.name, email: a.uploader.email },
  }));

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <div className="flex items-center gap-3 flex-wrap">
          <h1 className="text-2xl font-bold">{task.title}</h1>
          <Badge variant={STATUS_VARIANT[task.status]}>
            {task.status.replace("_", " ")}
          </Badge>
        </div>
        <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
          {task.project.name}{task.dueDate ? ` · Due ${formatDate(task.dueDate)}` : ""}
        </p>
      </div>

      <Card>
        <CardContent className="pt-6">
          <TaskDescription
            orgSlug={orgSlug}
            taskId={taskId}
            description={task.description}
            readOnly
          />
        </CardContent>
      </Card>

      <Card>
        <CardContent className="pt-6">
          <TaskAttachments
            orgSlug={orgSlug}
            taskId={taskId}
            attachments={attachments}
          />
        </CardContent>
      </Card>

      <Card>
        <CardContent className="pt-6">
          <TaskComments
            orgSlug={orgSlug}
            taskId={taskId}
            comments={comments}
            currentUserId={session.user.id}
            isStaff={false}
          />
        </CardContent>
      </Card>
    </div>
  );
}
