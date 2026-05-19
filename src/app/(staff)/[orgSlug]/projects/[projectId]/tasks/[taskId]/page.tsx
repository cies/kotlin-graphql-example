import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ArrowLeft } from "lucide-react";
import { formatMinutes } from "@/lib/utils/format";
import { getTaskHourSummary } from "@/lib/actions/time-entries";
import { TaskStatus } from "@prisma/client";
import { TaskDescription } from "@/components/tasks/task-description";
import { TaskAttachments } from "@/components/tasks/task-attachments";
import { TaskActivityTimeline } from "@/components/tasks/task-activity-timeline";
import { TaskEditMeta } from "@/components/tasks/task-edit-meta";
import { TaskTitleEdit } from "@/components/tasks/task-title-edit";
import { TaskTimeEntriesCard } from "@/components/time/task-time-entries-card";

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
  params: Promise<{ orgSlug: string; projectId: string; taskId: string }>;
}

export default async function TaskDetailPage({ params }: Props) {
  const { orgSlug, projectId, taskId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const task = await prisma.task.findUnique({
    where: { id: taskId, organizationId: org.id },
    include: {
      project: {
        select: {
          name: true,
          currency: true,
          customer: {
            select: {
              companyName: true,
              firstName: true,
              lastName: true,
              type: true,
            },
          },
        },
      },
      timeEntries: {
        orderBy: { workSortAt: "desc" },
        take: 5,
        include: { user: { select: { name: true, email: true } } },
      },
      comments: {
        orderBy: { createdAt: "asc" },
        include: { author: { select: { id: true, name: true, email: true } } },
      },
      attachments: {
        orderBy: { createdAt: "asc" },
        include: { uploader: { select: { name: true, email: true } } },
      },
      activities: {
        orderBy: { createdAt: "asc" },
      },
    },
  });

  if (!task) notFound();

  const summary = await getTaskHourSummary(org.id, taskId);

  // All org members for assignee picker
  const allMembers = await prisma.organizationMember.findMany({
    where: { organizationId: org.id },
    include: { user: { select: { id: true, name: true, email: true } } },
  });

  const memberMap: Record<string, string> = {};
  allMembers.forEach((m) => {
    memberMap[m.user.id] = m.user.name ?? m.user.email;
  });

  const comments = task.comments.map((c) => ({
    id: c.id,
    body: c.body,
    createdAt: c.createdAt.toISOString(),
    author: { id: c.author.id, name: c.author.name, email: c.author.email },
  }));

  const activities = task.activities.map((a) => ({
    id: a.id,
    type: a.type,
    actorName: a.actorName,
    metadata: a.metadata as Record<string, unknown> | null,
    createdAt: a.createdAt.toISOString(),
  }));

  const rateHistory = task.activities
    .filter((a) => a.type === "RATE_CHANGED")
    .map((a) => ({
      id: a.id,
      actorName: a.actorName,
      metadata: a.metadata as Record<string, unknown> | null,
      createdAt: a.createdAt.toISOString(),
    }));

  const attachments = task.attachments.map((a) => ({
    id: a.id,
    filename: a.filename,
    mimeType: a.mimeType,
    sizeBytes: a.sizeBytes,
    createdAt: a.createdAt.toISOString(),
    uploader: { name: a.uploader.name, email: a.uploader.email },
  }));

  const customerDisplayName =
    task.project.customer.type === "B2B"
      ? task.project.customer.companyName?.trim() || "-"
      : `${task.project.customer.firstName ?? ""} ${task.project.customer.lastName ?? ""}`.trim() ||
        "-";

  const timeEntriesForCard = [...task.timeEntries].reverse();

  const taskTimeEntryItems = timeEntriesForCard.map((e) => ({
    id: e.id,
    userId: e.userId,
    userName: e.user.name,
    userEmail: e.user.email,
    startedAt: e.startedAt?.toISOString() ?? null,
    endedAt: e.endedAt?.toISOString() ?? null,
    manualMinutes: e.manualMinutes,
    loggedDate: e.loggedDate ? e.loggedDate.toISOString() : null,
    description: e.description,
    billed: e.billed,
    createdAt: e.createdAt.toISOString(),
  }));

  return (
    <div>
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/projects/${projectId}`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div className="flex-1">
          <div className="flex items-center gap-3 flex-wrap">
            <TaskTitleEdit
              orgSlug={orgSlug}
              projectId={projectId}
              taskId={taskId}
              initialTitle={task.title}
            />
            <Badge variant={STATUS_VARIANT[task.status]}>
              {task.status.replace("_", " ")}
            </Badge>
          </div>
          <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
            {task.project.name}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Left column */}
        <div className="lg:col-span-2 space-y-6">
          <Card>
            <CardContent className="pt-6">
              <TaskDescription
                orgSlug={orgSlug}
                taskId={taskId}
                description={task.description}
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

          {/* Unified activity + comments timeline */}
          <Card>
            <CardContent className="pt-6">
              <TaskActivityTimeline
                orgSlug={orgSlug}
                taskId={taskId}
                comments={comments}
                activities={activities}
                currentUserId={session.user.id}
                isStaff={true}
                memberMap={memberMap}
              />
            </CardContent>
          </Card>
        </div>

        {/* Right column */}
        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>Details</CardTitle>
            </CardHeader>
            <CardContent>
              <TaskEditMeta
                orgSlug={orgSlug}
                projectId={projectId}
                taskId={taskId}
                currency={task.project.currency}
                status={task.status}
                assigneeIds={task.assigneeIds}
                hourlyRate={task.hourlyRate ? task.hourlyRate.toString() : null}
                dueDate={task.dueDate}
                allMembers={allMembers.map((m) => ({
                  id: m.user.id,
                  name: m.user.name,
                  email: m.user.email,
                }))}
                rateHistory={rateHistory}
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Hour Summary</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              {[
                { label: "Total logged", value: summary.loggedMinutes, color: "" },
                { label: "Billed", value: summary.billedMinutes, color: "text-blue-600" },
                { label: "Paid", value: summary.paidMinutes, color: "text-emerald-600" },
                { label: "Unbilled", value: summary.unbilledMinutes, color: "text-amber-600" },
                { label: "Billed (unpaid)", value: summary.unpaidBilledMinutes, color: "text-orange-600" },
              ].map((r) => (
                <div key={r.label} className="flex justify-between">
                  <span className="text-[var(--muted-foreground)]">{r.label}</span>
                  <span className={`font-medium ${r.color}`}>{formatMinutes(r.value)}</span>
                </div>
              ))}
              {task.estimatedHours && (
                <>
                  <div className="my-2 border-t border-[var(--border)]" />
                  <div className="flex justify-between">
                    <span className="text-[var(--muted-foreground)]">Estimated</span>
                    <span className="font-medium">{task.estimatedHours.toString()}h</span>
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          {task.timeEntries.length > 0 && (
            <TaskTimeEntriesCard
              orgSlug={orgSlug}
              subtitlePrefix={`${task.project.name} · ${customerDisplayName}`}
              entries={taskTimeEntryItems}
            />
          )}
        </div>
      </div>
    </div>
  );
}
