"use client";

import { useState } from "react";
import {
  DragDropContext,
  Droppable,
  Draggable,
  DropResult,
} from "@hello-pangea/dnd";
import { TaskStatus } from "@prisma/client";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { updateTask, deleteTask } from "@/lib/actions/tasks";
import { Trash2, Clock } from "lucide-react";
import { formatDate } from "@/lib/utils/format";
import { startTimer } from "@/lib/actions/time-entries";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";

const COLUMNS: { status: TaskStatus; label: string; color: string }[] = [
  { status: "TODO", label: "To Do", color: "bg-slate-100 dark:bg-slate-800" },
  { status: "IN_PROGRESS", label: "In Progress", color: "bg-blue-50 dark:bg-blue-950" },
  { status: "REVIEW", label: "Review", color: "bg-amber-50 dark:bg-amber-950" },
  { status: "DONE", label: "Done", color: "bg-green-50 dark:bg-green-950" },
  { status: "CANCELLED", label: "Cancelled", color: "bg-slate-50 dark:bg-slate-900" },
];

const PRIORITY_LABELS: Record<number, { label: string; color: string }> = {
  0: { label: "None", color: "" },
  1: { label: "Low", color: "text-slate-500 border-slate-300" },
  2: { label: "Medium", color: "text-blue-600 border-blue-300" },
  3: { label: "High", color: "text-orange-500 border-orange-300" },
  4: { label: "Urgent", color: "text-red-600 border-red-300" },
};

interface Task {
  id: string;
  title: string;
  status: TaskStatus;
  assigneeIds: string[];
  priority: number;
  dueDate: Date | null;
  estimatedHours: unknown;
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

function AssigneeAvatars({ assigneeIds, members }: { assigneeIds: string[]; members: Member[] }) {
  const assigned = members.filter((m) => assigneeIds.includes(m.id));
  if (assigned.length === 0) return null;

  return (
    <div className="flex -space-x-1.5">
      {assigned.slice(0, 3).map((m) => {
        const initials = (m.name || m.email).slice(0, 2).toUpperCase();
        return m.image ? (
          <img
            key={m.id}
            src={m.image}
            alt={m.name || m.email}
            title={m.name || m.email}
            className="h-5 w-5 rounded-full ring-1 ring-white object-cover"
          />
        ) : (
          <div
            key={m.id}
            title={m.name || m.email}
            className="h-5 w-5 rounded-full ring-1 ring-white bg-[var(--primary)] text-white flex items-center justify-center text-[9px] font-semibold"
          >
            {initials}
          </div>
        );
      })}
      {assigned.length > 3 && (
        <div className="h-5 w-5 rounded-full ring-1 ring-white bg-[var(--muted)] text-[var(--muted-foreground)] flex items-center justify-center text-[9px] font-semibold">
          +{assigned.length - 3}
        </div>
      )}
    </div>
  );
}

export function TaskBoard({ orgSlug, projectId, tasks: initialTasks, members }: Props) {
  const [tasks, setTasks] = useState(initialTasks);
  const [deleteTaskId, setDeleteTaskId] = useState<string | null>(null);

  function getColumnTasks(status: TaskStatus) {
    return tasks
      .filter((t) => t.status === status)
      .sort((a, b) => b.priority - a.priority);
  }

  async function handleDragEnd(result: DropResult) {
    if (!result.destination) return;
    const newStatus = result.destination.droppableId as TaskStatus;
    const taskId = result.draggableId;

    const task = tasks.find((t) => t.id === taskId);
    if (!task || task.status === newStatus) return;

    // Optimistic update
    setTasks((prev) =>
      prev.map((t) => (t.id === taskId ? { ...t, status: newStatus } : t))
    );

    const res = await updateTask(orgSlug, projectId, taskId, { status: newStatus });
    if (res.error) {
      // Revert on error
      setTasks((prev) =>
        prev.map((t) => (t.id === taskId ? { ...t, status: task.status } : t))
      );
    }
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

  const priority = (p: number) => PRIORITY_LABELS[p] ?? PRIORITY_LABELS[0];

  return (
    <DragDropContext onDragEnd={handleDragEnd}>
      <div className="flex gap-3 overflow-x-auto pb-4">
        {COLUMNS.map((col) => {
          const colTasks = getColumnTasks(col.status);
          return (
            <div key={col.status} className="flex-shrink-0 w-64">
              <div className={`rounded-lg ${col.color} border border-[var(--border)]`}>
                <div className="px-3 py-2.5 border-b border-[var(--border)] flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wider text-[var(--muted-foreground)]">
                    {col.label}
                  </span>
                  <span className="text-xs text-[var(--muted-foreground)] bg-white/50 dark:bg-black/20 rounded-full px-1.5 py-0.5 font-medium">
                    {colTasks.length}
                  </span>
                </div>

                <Droppable droppableId={col.status}>
                  {(provided, snapshot) => (
                    <div
                      ref={provided.innerRef}
                      {...provided.droppableProps}
                      className={`p-2 space-y-2 min-h-[120px] transition-colors ${
                        snapshot.isDraggingOver ? "bg-[var(--primary)]/5" : ""
                      }`}
                    >
                      {colTasks.map((task, index) => {
                        const p = priority(task.priority);
                        const isOverdue =
                          task.dueDate && task.dueDate < new Date() && task.status !== "DONE";

                        return (
                          <Draggable key={task.id} draggableId={task.id} index={index}>
                            {(drag, dragSnapshot) => (
                              <div
                                ref={drag.innerRef}
                                {...drag.draggableProps}
                                {...drag.dragHandleProps}
                                className={`bg-white dark:bg-slate-900 rounded-md border border-[var(--border)] p-2.5 shadow-sm transition-shadow ${
                                  dragSnapshot.isDragging ? "shadow-lg rotate-1" : "hover:shadow-md"
                                }`}
                              >
                                <div className="flex items-start justify-between gap-1 mb-1.5">
                                  <Link
                                    href={`/${orgSlug}/projects/${projectId}/tasks/${task.id}`}
                                    className="text-xs font-medium leading-snug hover:text-[var(--primary)] hover:underline line-clamp-2"
                                    onClick={(e) => dragSnapshot.isDragging && e.preventDefault()}
                                  >
                                    {task.title}
                                  </Link>
                                  <div className="flex gap-0.5 shrink-0">
                                    <button
                                      onClick={() => handleStartTimer(task.id)}
                                      className="p-0.5 rounded text-[var(--muted-foreground)] hover:text-[var(--primary)] transition-colors"
                                      title="Start timer"
                                    >
                                      <Clock className="h-3 w-3" />
                                    </button>
                                    <button
                                      onClick={() => setDeleteTaskId(task.id)}
                                      className="p-0.5 rounded text-[var(--muted-foreground)] hover:text-[var(--destructive)] transition-colors"
                                      title="Delete task"
                                    >
                                      <Trash2 className="h-3 w-3" />
                                    </button>
                                  </div>
                                </div>

                                <div className="flex items-center justify-between gap-2">
                                  <div className="flex items-center gap-1.5 flex-wrap">
                                    {task.priority > 0 && (
                                      <span
                                        className={`text-[10px] font-medium border rounded px-1 py-0 ${p.color}`}
                                      >
                                        {p.label}
                                      </span>
                                    )}
                                    {task.dueDate && (
                                      <span
                                        className={`text-[10px] ${
                                          isOverdue
                                            ? "text-[var(--destructive)] font-medium"
                                            : "text-[var(--muted-foreground)]"
                                        }`}
                                      >
                                        {formatDate(task.dueDate)}
                                      </span>
                                    )}
                                  </div>
                                  <AssigneeAvatars
                                    assigneeIds={task.assigneeIds}
                                    members={members}
                                  />
                                </div>
                              </div>
                            )}
                          </Draggable>
                        );
                      })}
                      {provided.placeholder}
                    </div>
                  )}
                </Droppable>
              </div>
            </div>
          );
        })}
      </div>
      <ConfirmDialog
        open={!!deleteTaskId}
        title="Delete task?"
        description="This task and all its time entries will be permanently deleted."
        confirmLabel="Delete"
        onConfirm={handleDeleteConfirmed}
        onCancel={() => setDeleteTaskId(null)}
      />
    </DragDropContext>
  );
}
