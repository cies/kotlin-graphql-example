"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { deleteTimeEntry } from "@/lib/actions/time-entries";
import { Trash2, Loader2, List } from "lucide-react";
import { formatDate, formatMinutes } from "@/lib/utils/format";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";
import { TimeEntryRow } from "@/components/time/time-entry-row";
import { TimeBillingPills } from "@/components/time/time-billing-pills";

interface Entry {
  id: string;
  taskId: string;
  taskTitle: string;
  projectName: string;
  customerName: string;
  startedAt: Date | null;
  endedAt: Date | null;
  manualMinutes: number | null;
  loggedDate: Date | null;
  description: string | null;
  billed: boolean;
  createdAt: Date;
}

interface Props {
  orgSlug: string;
  entries: Entry[];
}

function displayWorkDate(e: Entry): Date {
  if (e.loggedDate) return new Date(e.loggedDate);
  if (e.manualMinutes != null) return new Date(e.createdAt);
  if (e.startedAt) return new Date(e.startedAt);
  return new Date(e.createdAt);
}

function calcMinutes(e: Entry): number {
  if (e.manualMinutes) return e.manualMinutes;
  if (e.startedAt && e.endedAt) {
    return Math.round(
      (new Date(e.endedAt).getTime() - new Date(e.startedAt).getTime()) / 60000
    );
  }
  return 0;
}

export function TimeEntriesList({ orgSlug, entries }: Props) {
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);

  async function handleDeleteConfirmed() {
    if (!pendingDeleteId) return;
    const id = pendingDeleteId;
    setPendingDeleteId(null);
    setDeletingId(id);
    await deleteTimeEntry(orgSlug, id);
    setDeletingId(null);
    toast.success("Time entry deleted");
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 space-y-0 pb-4">
        <CardTitle className="flex items-center gap-2 text-base font-semibold">
          <List className="h-4 w-4" />
          Recent entries
        </CardTitle>
        <TimeBillingPills />
      </CardHeader>
      <CardContent className="pt-0">
        {entries.length === 0 ? (
          <p className="text-center text-sm text-[var(--muted-foreground)] py-10">
            No time entries yet.
          </p>
        ) : (
          <div>
            {entries.map((e) => {
              const mins = calcMinutes(e);
              const isRunning = Boolean(e.startedAt && !e.endedAt && !e.manualMinutes);
              const subtitle = `${e.projectName} · ${e.customerName} · ${formatDate(displayWorkDate(e))}`;
              return (
                <TimeEntryRow
                  key={e.id}
                  accentKey={e.taskId}
                  primaryText={e.taskTitle}
                  subtitle={subtitle}
                  description={e.description}
                  durationLabel={formatMinutes(mins)}
                  isRunning={isRunning}
                  billed={e.billed}
                  actions={
                    !e.billed && !isRunning ? (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
                        onClick={() => setPendingDeleteId(e.id)}
                        disabled={deletingId === e.id}
                      >
                        {deletingId === e.id ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <Trash2 className="h-3.5 w-3.5" />
                        )}
                      </Button>
                    ) : null
                  }
                />
              );
            })}
          </div>
        )}
      </CardContent>

      <ConfirmDialog
        open={!!pendingDeleteId}
        title="Delete time entry?"
        description="This time entry will be permanently removed."
        confirmLabel="Delete"
        onConfirm={handleDeleteConfirmed}
        onCancel={() => setPendingDeleteId(null)}
      />
    </Card>
  );
}
