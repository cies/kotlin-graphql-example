import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TaskListView } from "@/components/projects/task-list-view";
import { TaskBoard } from "@/components/projects/task-board";
import { ArrowLeft, List, Kanban } from "lucide-react";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { cn } from "@/lib/utils/cn";
import { ProjectDetailToolbar } from "@/components/projects/project-detail-toolbar";

interface Props {
  params: Promise<{ orgSlug: string; projectId: string }>;
  searchParams: Promise<{ view?: string }>;
}

export default async function ProjectDetailPage({ params, searchParams }: Props) {
  const { orgSlug, projectId } = await params;
  const { view } = await searchParams;
  const isBoardView = view === "board";

  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const staffMember = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
    select: { pinnedProjectIds: true },
  });
  if (!staffMember) redirect("/auth/login");

  const project = await prisma.project.findUnique({
    where: { id: projectId, organizationId: org.id },
    include: {
      customer: true,
      tasks: {
        orderBy: [{ status: "asc" }, { priority: "desc" }, { createdAt: "asc" }],
        include: {
          _count: { select: { timeEntries: true } },
        },
      },
    },
  });

  if (!project) notFound();

  const tasksForClient = project.tasks.map((t) => ({
    ...t,
    estimatedHours: t.estimatedHours != null ? Number(t.estimatedHours) : null,
    hourlyRate: t.hourlyRate != null ? Number(t.hourlyRate) : null,
  }));

  const members = await prisma.organizationMember.findMany({
    where: { organizationId: org.id },
    include: { user: { select: { id: true, name: true, email: true, image: true } } },
  });

  const memberUsers = members.map((m) => ({
    id: m.user.id,
    name: m.user.name,
    email: m.user.email,
    image: m.user.image,
  }));

  const customerName =
    project.customer.type === "B2B"
      ? project.customer.companyName
      : `${project.customer.firstName || ""} ${project.customer.lastName || ""}`.trim();

  const listHref = `/${orgSlug}/projects/${projectId}`;
  const boardHref = `/${orgSlug}/projects/${projectId}?view=board`;
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: org.id },
    select: { numberFormatStyle: true },
  });
  const numberFormatStyle = normalizeNumberFormatStyle(settings?.numberFormatStyle);

  const isPinned = staffMember.pinnedProjectIds.includes(projectId);
  const invoiceHref = `/${orgSlug}/invoices/new?customerId=${encodeURIComponent(project.customerId)}&projectId=${encodeURIComponent(projectId)}`;

  return (
    <div>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between mb-6">
        <div className="flex items-start gap-4 min-w-0">
          <Button variant="ghost" size="icon" className="shrink-0 mt-0.5" asChild>
            <Link href={`/${orgSlug}/projects`}>
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2 gap-y-1">
              <h1 className="text-2xl font-bold">{project.name}</h1>
              <Badge variant="outline">{project.status.replace(/_/g, " ")}</Badge>
            </div>
            <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
              {customerName} · Created {formatDate(project.createdAt)}
            </p>
          </div>
        </div>
        <ProjectDetailToolbar
          orgSlug={orgSlug}
          projectId={projectId}
          isPinned={isPinned}
          exportHref={`/${orgSlug}/projects/${projectId}/export`}
          editHref={`/${orgSlug}/projects/${projectId}/edit`}
          newTaskHref={`/${orgSlug}/projects/${projectId}/tasks/new`}
          invoiceHref={invoiceHref}
          customerPreviewHref={`/${orgSlug}/projects/${projectId}/customer-preview`}
        />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-4">
        <div className="lg:col-span-3">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between gap-2">
              <CardTitle>Tasks</CardTitle>

              <div className="flex items-center gap-2">
                {/* View toggle */}
                <div className="flex items-center rounded-md border border-[var(--border)] overflow-hidden">
                  <Link
                    href={listHref}
                    className={cn(
                      "flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium transition-colors",
                      !isBoardView
                        ? "bg-[var(--primary)] text-white"
                        : "hover:bg-[var(--muted)]"
                    )}
                  >
                    <List className="h-3.5 w-3.5" />
                    List
                  </Link>
                  <Link
                    href={boardHref}
                    className={cn(
                      "flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium transition-colors border-l border-[var(--border)]",
                      isBoardView
                        ? "bg-[var(--primary)] text-white"
                        : "hover:bg-[var(--muted)]"
                    )}
                  >
                    <Kanban className="h-3.5 w-3.5" />
                    Board
                  </Link>
                </div>
              </div>
            </CardHeader>

            <CardContent className={isBoardView ? "overflow-x-auto" : ""}>
              {isBoardView ? (
                <TaskBoard
                  orgSlug={orgSlug}
                  projectId={projectId}
                  tasks={tasksForClient}
                  members={memberUsers}
                />
              ) : (
                <TaskListView
                  orgSlug={orgSlug}
                  projectId={projectId}
                  tasks={tasksForClient}
                  members={memberUsers}
                />
              )}
            </CardContent>
          </Card>
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>Project Info</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div>
                <p className="text-[var(--muted-foreground)]">Billing mode</p>
                <Badge variant="outline">{project.billingMode}</Badge>
              </div>
              {project.billingMode === "HOURLY" && project.hourlyRate && (
                <div>
                  <p className="text-[var(--muted-foreground)]">Hourly rate</p>
                  <p className="font-medium">
                    {formatCurrency(Number(project.hourlyRate), project.currency, numberFormatStyle)}/h
                  </p>
                </div>
              )}
              {project.billingMode === "FIXED" && project.fixedFee && (
                <div>
                  <p className="text-[var(--muted-foreground)]">Fixed fee</p>
                  <p className="font-medium">
                    {formatCurrency(Number(project.fixedFee), project.currency, numberFormatStyle)}
                  </p>
                </div>
              )}
              <div>
                <p className="text-[var(--muted-foreground)]">Auto-invoice</p>
                <p className="font-medium">{project.autoInvoice ? "Enabled" : "Disabled"}</p>
              </div>
              {project.startDate && (
                <div>
                  <p className="text-[var(--muted-foreground)]">Start date</p>
                  <p className="font-medium">{formatDate(project.startDate)}</p>
                </div>
              )}
              {project.endDate && (
                <div>
                  <p className="text-[var(--muted-foreground)]">End date</p>
                  <p className="font-medium">{formatDate(project.endDate)}</p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
