"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Check, Loader2, Pencil, X } from "lucide-react";
import { updateTask } from "@/lib/actions/tasks";
import { TaskStatus } from "@prisma/client";

const STATUS_LABELS: Record<TaskStatus, string> = {
  TODO: "To do",
  IN_PROGRESS: "In progress",
  REVIEW: "Review",
  DONE: "Done",
  CANCELLED: "Cancelled",
};

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

interface Member {
  id: string;
  name: string | null;
  email: string;
}

interface RateActivity {
  id: string;
  actorName: string | null;
  metadata: Record<string, unknown> | null;
  createdAt: string;
}

interface Props {
  orgSlug: string;
  projectId: string;
  taskId: string;
  currency: string;
  status: TaskStatus;
  assigneeIds: string[];
  hourlyRate: string | null;
  dueDate: Date | null;
  allMembers: Member[];
  rateHistory: RateActivity[];
}

// ─── Status editor ────────────────────────────────────────────────────────────

function StatusEditor({
  orgSlug, projectId, taskId, status,
}: Pick<Props, "orgSlug" | "projectId" | "taskId" | "status">) {
  const [saving, setSaving] = useState(false);

  async function handleChange(val: string) {
    setSaving(true);
    await updateTask(orgSlug, projectId, taskId, { status: val as TaskStatus });
    setSaving(false);
  }

  return (
    <div>
      <p className="text-[var(--muted-foreground)] text-xs mb-1">Status</p>
      <div className="flex items-center gap-2">
        <Select value={status} onValueChange={handleChange} disabled={saving}>
          <SelectTrigger className="h-8 text-xs w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(Object.keys(STATUS_LABELS) as TaskStatus[]).map((s) => (
              <SelectItem key={s} value={s} className="text-xs">
                {STATUS_LABELS[s]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {saving && <Loader2 className="h-3.5 w-3.5 animate-spin text-[var(--muted-foreground)]" />}
      </div>
    </div>
  );
}

// ─── Assignees editor ─────────────────────────────────────────────────────────

function AssigneesEditor({
  orgSlug, projectId, taskId, assigneeIds, allMembers,
}: Pick<Props, "orgSlug" | "projectId" | "taskId" | "assigneeIds" | "allMembers">) {
  const [selected, setSelected] = useState<string[]>(assigneeIds);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  function toggle(id: string) {
    setSelected((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
    setDirty(true);
  }

  async function save() {
    setSaving(true);
    await updateTask(orgSlug, projectId, taskId, { assigneeIds: selected });
    setSaving(false);
    setDirty(false);
  }

  return (
    <div>
      <p className="text-[var(--muted-foreground)] text-xs mb-1">Assignees</p>
      <div className="space-y-1">
        {allMembers.map((m) => {
          const isSelected = selected.includes(m.id);
          return (
            <button
              key={m.id}
              onClick={() => toggle(m.id)}
              className={`flex items-center gap-2 w-full rounded px-2 py-1.5 text-sm text-left transition-colors ${
                isSelected
                  ? "bg-[var(--primary)]/10 text-[var(--primary)]"
                  : "hover:bg-[var(--muted)]"
              }`}
            >
              <div className="h-6 w-6 rounded-full bg-[var(--primary)]/20 flex items-center justify-center text-xs font-bold flex-shrink-0">
                {(m.name ?? m.email)[0]?.toUpperCase()}
              </div>
              <span className="truncate">{m.name ?? m.email}</span>
              {isSelected && <Check className="h-3.5 w-3.5 ml-auto flex-shrink-0" />}
            </button>
          );
        })}
      </div>
      {dirty && (
        <Button size="sm" className="mt-2 h-7 text-xs" onClick={save} disabled={saving}>
          {saving ? <Loader2 className="h-3 w-3 animate-spin mr-1" /> : null}
          Save
        </Button>
      )}
    </div>
  );
}

// ─── Hourly rate editor ───────────────────────────────────────────────────────

function RateEditor({
  orgSlug, projectId, taskId, currency, hourlyRate, rateHistory,
}: Pick<Props, "orgSlug" | "projectId" | "taskId" | "currency" | "hourlyRate" | "rateHistory">) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(hourlyRate ?? "");
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true);
    await updateTask(orgSlug, projectId, taskId, { hourlyRate: value || undefined });
    setSaving(false);
    setEditing(false);
  }

  return (
    <div>
      <p className="text-[var(--muted-foreground)] text-xs mb-1">Hourly rate</p>
      {editing ? (
        <div className="flex items-center gap-1">
          <span className="text-xs text-[var(--muted-foreground)]">{currency}</span>
          <Input
            className="h-7 w-24 text-sm"
            type="number"
            min="0"
            step="0.01"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            autoFocus
            onKeyDown={(e) => { if (e.key === "Enter") save(); if (e.key === "Escape") setEditing(false); }}
          />
          <button onClick={save} disabled={saving} className="text-[var(--primary)] hover:opacity-80">
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
          </button>
          <button onClick={() => setEditing(false)} className="text-[var(--muted-foreground)] hover:opacity-80">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      ) : (
        <button
          onClick={() => setEditing(true)}
          className="flex items-center gap-1.5 text-sm font-medium hover:text-[var(--primary)] transition-colors group"
        >
          {hourlyRate
            ? <>{currency} {parseFloat(hourlyRate).toFixed(2)}/h</>
            : <span className="text-[var(--muted-foreground)]">Not set</span>}
          <Pencil className="h-3 w-3 opacity-0 group-hover:opacity-60 transition-opacity" />
        </button>
      )}

      {rateHistory.length > 0 && (
        <div className="mt-2 space-y-0.5">
          <p className="text-xs text-[var(--muted-foreground)]">Rate history</p>
          {rateHistory.map((r) => {
            const m = r.metadata ?? {};
            const from = m.from != null ? `${currency} ${Number(m.from).toFixed(2)}/h` : "none";
            const to = m.to != null ? `${currency} ${Number(m.to).toFixed(2)}/h` : "none";
            return (
              <p key={r.id} className="text-xs text-[var(--muted-foreground)]">
                {from} → {to}
                <span className="opacity-60 ml-1">
                  {new Date(r.createdAt).toLocaleDateString("en-GB")}
                </span>
              </p>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Main component ───────────────────────────────────────────────────────────

export function TaskEditMeta({
  orgSlug, projectId, taskId, currency,
  status, assigneeIds, hourlyRate, dueDate,
  allMembers, rateHistory,
}: Props) {
  return (
    <div className="space-y-4 text-sm">
      <StatusEditor
        orgSlug={orgSlug}
        projectId={projectId}
        taskId={taskId}
        status={status}
      />

      <div>
        <p className="text-[var(--muted-foreground)] text-xs mb-1">Due date</p>
        <p className="font-medium">
          {dueDate
            ? new Date(dueDate).toLocaleDateString("en-GB", { dateStyle: "medium" })
            : <span className="text-[var(--muted-foreground)]">-</span>}
        </p>
      </div>

      <AssigneesEditor
        orgSlug={orgSlug}
        projectId={projectId}
        taskId={taskId}
        assigneeIds={assigneeIds}
        allMembers={allMembers}
      />

      <RateEditor
        orgSlug={orgSlug}
        projectId={projectId}
        taskId={taskId}
        currency={currency}
        hourlyRate={hourlyRate}
        rateHistory={rateHistory}
      />
    </div>
  );
}
