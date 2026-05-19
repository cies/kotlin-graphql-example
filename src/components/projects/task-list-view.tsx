"use client";

import { useState } from "react";
import { TaskStatus } from "@prisma/client";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { TaskHourSummary } from "./task-hour-summary";
import { updateTask, deleteTask } from "@/lib/actions/tasks";
import { startTimer } from "@/lib/actions/time-entries";
import { Trash2, Clock, ChevronDown, ChevronRight } from "lucide-react";
import { formatDate } from "@/lib/utils/format";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";

const STATUS_COLORS: Record<
  TaskStatus,
  "default" | "info" | "success" | "warning" | "secondary" | "destructive" | "outline"
> = {
  TODO: "secondary",
  IN_PROGRESS: "info",
  REVIEW: "warning",
  DONE: "success",
  CANCELLED: "outline",
};

const STATUS_LABELS: Record<TaskStatus, string> = {
  TODO: "To Do",
  IN_PROGRESS: "In Progress",
  REVIEW: "Review",
  DONE: "Done",
  CANCELLED: "Cancelled",
};

const STATUS_ORDER: TaskStatus[] = ["TODO", "IN_PROGRESS", "REVIEW", "DONE", "CANCELLED"];

const PRIORITY_CONFIG: Record<number, { label: string; className: string }> = {
  0: { label: "-", className: "text-[var(--muted-foreground)]" },
  1: { label: "Low", className: "text-slate-500 border-slate-300 bg-slate-50" },
  2: { label: "Medium", className: "text-blue-600 border-blue-300 bg-blue-50" },
  3: { label: "High", className: "text-orange-500 border-orange-300 bg-orange-50" },
  4: { label: "Urgent", className: "text-red-600 border-red-300 bg-red-50" },
};

interface Task {
  id: string;
  title: string;
  status: TaskStatus;
  assigneeIds: string[];
  priority: number;
  estimatedHours: unknown;
  dueDate: Date | null;
  _count: { timeEntries: number };
}

interface Member {
  id: string;
  name: string | null;
  email: string;
  image?: string | null;
}

interface Props {
  orgSlug: string;
  projectId: string;
  tasks: Task[];
  members: Member[];
}

function AssigneeAvatars({
  assigneeIds,
  members,
}: {
  assigneeIds: string[];
  members: Member[];
}) {
  const assigned = members.filter((m) => assigneeIds.includes(m.id));
  if (assigned.length === 0) {
    return <span className="text-xs text-[var(--muted-foreground)]">-</span>;
  }
  return (
    <div className="flex -space-x-1.5 items-center">
      {assigned.slice(0, 3).map((m) => {
        const initials = (m.name || m.email).slice(0, 2).toUpperCase();
        return m.image ? (
          <img
            key={m.id}
            src={m.image}
            alt={m.name || m.email}
            title={m.name || m.email}
            className="h-6 w-6 rounded-full ring-1 ring-white object-cover"
          />
        ) : (
          <div
            key={m.id}
            title={m.name || m.email}
            className="h-6 w-6 rounded-full ring-1 ring-white bg-[var(--primary)] text-white flex items-center justify-center text-[10px] font-semibold"
          >
            {initials}
          </div>
        );
      })}
      {assigned.length > 3 && (
        <span className="text-xs text-[var(--muted-foreground)] pl-2">
          +{assigned.length - 3}
        </span>
      )}
    </div>
  );
}

function PriorityBadge({ priority }: { priority: number }) {
  const cfg = PRIORITY_CONFIG[priority] ?? PRIORITY_CONFIG[0];
  if (priority === 0) {
    return <span className="text-xs text-[var(--muted-foreground)]">-</span>;
  }
  return (
    <span
      className={`text-xs font-medium border rounded px-1.5 py-0.5 ${cfg.className}`}
    >
      {cfg.label}
    </span>
  );
}

export function TaskListView({ orgSlug, projectId, tasks: initialTasks, members }: Props) {
  const [tasks, setTasks] = useState(initialTasks);
  const [collapsed, setCollapsed] = useState<Set<TaskStatus>>(new Set(["CANCELLED"]));
  const [hoursTaskId, setHoursTaskId] = useState<string | null>(null);
  const [deleteTaskId, setDeleteTaskId] = useState<string | null>(null);

  async function handleStatusChange(taskId: string, status: TaskStatus) {
    setTasks((prev) => prev.map((t) => (t.id === taskId ? { ...t, status } : t)));
    await updateTask(orgSlug, projectId, taskId, { status });
  }

  async function handleDeleteConfirmed() {
    if (!deleteTaskId) return;
    const id = deleteTaskId;
    setDeleteTaskId(null);
    setTasks((prev) => prev.filter((t) => t.id !== id));
    await deleteTask(orgSlug, projectId, id);
    toast.success("Task deleted");
  }

  async function handleStartTimer(taskId: string) {
    await startTimer(orgSlug, taskId);
  }

  function toggleCollapse(status: TaskStatus) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(status)) next.delete(status);
      else next.add(status);
      return next;
    });
  }

  if (tasks.length === 0) {
    return (
      <p className="text-sm text-[var(--muted-foreground)] text-center py-8">
        No tasks yet. Create the first one!
      </p>
    );
  }

  return (
    <>
      <div className="space-y-1">
        {/* Header row */}
        <div className="hidden md:grid grid-cols-[1fr_120px_100px_90px_100px_80px] gap-2 px-3 py-1 text-xs font-medium text-[var(--muted-foreground)] uppercase tracking-wider border-b border-[var(--border)]">
          <span>Task</span>
          <span>Assignees</span>
          <span>Priority</span>
          <span>Due date</span>
          <span>Status</span>
          <span></span>
        </div>

        {STATUS_ORDER.map((status) => {
          const group = tasks
            .filter((t) => t.status === status)
            .sort((a, b) => b.priority - a.priority);

          if (group.length === 0) return null;

          const isCollapsed = collapsed.has(status);

          return (
            <div key={status}>
              <button
                onClick={() => toggleCollapse(status)}
                className="w-full flex items-center gap-2 px-3 py-2 text-xs font-semibold uppercase tracking-wider text-[var(--muted-foreground)] hover:text-[var(--foreground)] hover:bg-[var(--muted)]/30 rounded transition-colors"
              >
                {isCollapsed ? (
                  <ChevronRight className="h-3.5 w-3.5" />
                ) : (
                  <ChevronDown className="h-3.5 w-3.5" />
                )}
                <Badge variant={STATUS_COLORS[status]} className="text-[10px] px-1.5 py-0">
                  {STATUS_LABELS[status]}
                </Badge>
                <span className="text-[var(--muted-foreground)] font-normal normal-case">
                  {group.length} task{group.length !== 1 ? "s" : ""}
                </span>
              </button>

              {!isCollapsed &&
                group.map((task) => {
                  const isOverdue =
                    task.dueDate &&
                    task.dueDate < new Date() &&
                    task.status !== "DONE" &&
                    task.status !== "CANCELLED";

                  return (
                    <div
                      key={task.id}
                      className="grid grid-cols-1 md:grid-cols-[1fr_120px_100px_90px_100px_80px] gap-2 items-center px-3 py-2.5 rounded-md hover:bg-[var(--muted)]/30 transition-colors border border-transparent hover:border-[var(--border)]"
                    >
                      {/* Title */}
                      <div>
                        <Link
                          href={`/${orgSlug}/projects/${projectId}/tasks/${task.id}`}
                          className="text-sm font-medium hover:text-[var(--primary)] hover:underline"
                        >
                          {task.title}
                        </Link>
                        {task._count.timeEntries > 0 && (
                          <button
                            onClick={() => setHoursTaskId(task.id)}
                            className="ml-2 text-xs text-[var(--primary)] hover:underline"
                          >
                            {task._count.timeEntries} entries
                          </button>
                        )}
                      </div>

                      {/* Assignees */}
                      <div>
                        <AssigneeAvatars
                          assigneeIds={task.assigneeIds}
                          members={members}
                        />
                      </div>

                      {/* Priority */}
                      <div>
                        <PriorityBadge priority={task.priority} />
                      </div>

                      {/* Due date */}
                      <div>
                        {task.dueDate ? (
                          <span
                            className={`text-xs ${
                              isOverdue
                                ? "text-[var(--destructive)] font-medium"
                                : "text-[var(--muted-foreground)]"
                            }`}
                          >
                            {formatDate(task.dueDate)}
                          </span>
                        ) : (
                          <span className="text-xs text-[var(--muted-foreground)]">-</span>
                        )}
                      </div>

                      {/* Status */}
                      <div>
                        <Select
                          value={task.status}
                          onValueChange={(v) =>
                            handleStatusChange(task.id, v as TaskStatus)
                          }
                        >
                          <SelectTrigger className="h-7 w-[100px] text-xs">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {STATUS_ORDER.map((s) => (
                              <SelectItem key={s} value={s} className="text-xs">
                                {STATUS_LABELS[s]}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>

                      {/* Actions */}
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => handleStartTimer(task.id)}
                          title="Start timer"
                        >
                          <Clock className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
                          onClick={() => setDeleteTaskId(task.id)}
                          title="Delete task"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </div>
                  );
                })}
            </div>
          );
        })}
      </div>

      <Dialog open={!!hoursTaskId} onOpenChange={(o) => !o && setHoursTaskId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Time Summary</DialogTitle>
          </DialogHeader>
          {hoursTaskId && (
            <TaskHourSummary
              orgSlug={orgSlug}
              projectId={projectId}
              taskId={hoursTaskId}
            />
          )}
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTaskId}
        title="Delete task?"
        description="This task and all its time entries will be permanently deleted."
        confirmLabel="Delete"
        onConfirm={handleDeleteConfirmed}
        onCancel={() => setDeleteTaskId(null)}
      />
    </>
  );
}
