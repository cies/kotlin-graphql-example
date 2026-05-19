"use client";

import { useState } from "react";
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
import { Trash2, Clock, ChevronRight } from "lucide-react";
import { formatDate } from "@/lib/utils/format";
import { TaskStatus } from "@prisma/client";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";

const STATUS_COLORS: Record<TaskStatus, "default" | "info" | "success" | "warning" | "secondary" | "destructive" | "outline"> = {
  TODO: "secondary",
  IN_PROGRESS: "info",
  REVIEW: "warning",
  DONE: "success",
  CANCELLED: "outline",
};

interface Task {
  id: string;
  title: string;
  status: TaskStatus;
  assigneeIds: string[];
  estimatedHours: unknown;
  dueDate: Date | null;
  _count: { timeEntries: number };
}

interface Member {
  id: string;
  name: string | null;
  email: string;
}

interface Props {
  orgSlug: string;
  projectId: string;
  tasks: Task[];
  members: Member[];
}

export function TaskList({ orgSlug, projectId, tasks, members }: Props) {
  const [hoursTaskId, setHoursTaskId] = useState<string | null>(null);
  const [deleteTaskId, setDeleteTaskId] = useState<string | null>(null);

  async function handleStatusChange(taskId: string, status: TaskStatus) {
    await updateTask(orgSlug, projectId, taskId, { status });
  }

  async function handleDeleteConfirmed() {
    if (!deleteTaskId) return;
    const id = deleteTaskId;
    setDeleteTaskId(null);
    await deleteTask(orgSlug, projectId, id);
    toast.success("Task deleted");
  }

  async function handleStartTimer(taskId: string) {
    await startTimer(orgSlug, taskId);
  }

  if (tasks.length === 0) {
    return (
      <p className="text-sm text-[var(--muted-foreground)] text-center py-4">
        No tasks yet. Create the first one!
      </p>
    );
  }

  return (
    <>
      <div className="space-y-2">
        {tasks.map((task) => (
          <div
            key={task.id}
            className="flex items-center gap-3 rounded-md border border-[var(--border)] p-3 hover:bg-[var(--muted)]/30 transition-colors"
          >
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <Link
                  href={`/${orgSlug}/projects/${projectId}/tasks/${task.id}`}
                  className="font-medium text-sm hover:text-[var(--primary)] hover:underline truncate"
                >
                  {task.title}
                </Link>
              </div>
              <div className="flex items-center gap-2 mt-1">
                {task.dueDate && (
                  <span className="text-xs text-[var(--muted-foreground)]">
                    Due {formatDate(task.dueDate)}
                  </span>
                )}
                {task._count.timeEntries > 0 && (
                  <button
                    onClick={() => setHoursTaskId(task.id)}
                    className="text-xs text-[var(--primary)] hover:underline"
                  >
                    {task._count.timeEntries} entries
                  </button>
                )}
              </div>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              <Select
                value={task.status}
                onValueChange={(v) => handleStatusChange(task.id, v as TaskStatus)}
              >
                <SelectTrigger className="h-7 w-32 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {Object.keys(STATUS_COLORS).map((s) => (
                    <SelectItem key={s} value={s}>{s.replace("_", " ")}</SelectItem>
                  ))}
                </SelectContent>
              </Select>

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
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>
        ))}
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
