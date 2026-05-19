"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { deleteTimeEntry } from "@/lib/actions/time-entries";
import { Trash2, Loader2 } from "lucide-react";
import { formatDate, formatMinutes } from "@/lib/utils/format";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";
import { TimeEntryRow } from "@/components/time/time-entry-row";
import { cn } from "@/lib/utils/cn";

export interface TaskTimeEntryItem {
  id: string;
  userId: string;
  userName: string | null;
  userEmail: string;
  startedAt: string | null;
  endedAt: string | null;
  manualMinutes: number | null;
  loggedDate: string | null;
  description: string | null;
  billed: boolean;
  createdAt: string;
}

interface Props {
  orgSlug: string;
  subtitlePrefix: string;
  entries: TaskTimeEntryItem[];
}

type BillingFilter = "unbilled" | "billed";

function displayWorkDate(e: TaskTimeEntryItem): Date {
  if (e.loggedDate) return new Date(e.loggedDate);
  if (e.manualMinutes != null) return new Date(e.createdAt);
  if (e.startedAt) return new Date(e.startedAt);
  return new Date(e.createdAt);
}

function calcMinutes(e: TaskTimeEntryItem): number | null {
  if (e.manualMinutes != null) return e.manualMinutes;
  if (e.startedAt && e.endedAt) {
    return Math.round(
      (new Date(e.endedAt).getTime() - new Date(e.startedAt).getTime()) / 60000
    );
  }
  return null;
}

export function TaskTimeEntriesCard({ orgSlug, subtitlePrefix, entries }: Props) {
  const router = useRouter();
  const [billing, setBilling] = useState<BillingFilter>("unbilled");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    return entries.filter((e) =>
      billing === "billed" ? e.billed : !e.billed
    );
  }, [entries, billing]);

  async function handleDeleteConfirmed() {
    if (!pendingDeleteId) return;
    const id = pendingDeleteId;
    setPendingDeleteId(null);
    setDeletingId(id);
    await deleteTimeEntry(orgSlug, id);
    setDeletingId(null);
    toast.success("Time entry deleted");
    router.refresh();
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 space-y-0 pb-4">
        <CardTitle>Time Entries</CardTitle>
        <div className="flex rounded-lg border border-[var(--border)] p-0.5 bg-[var(--muted)]/40">
          <button
            type="button"
            onClick={() => setBilling("unbilled")}
            className={cn(
              "rounded-md px-3 py-1 text-xs font-medium transition-colors",
              billing === "unbilled"
                ? "bg-[var(--primary)] text-white shadow-sm"
                : "text-[var(--muted-foreground)] hover:text-foreground"
            )}
          >
            Unbilled
          </button>
          <button
            type="button"
            onClick={() => setBilling("billed")}
            className={cn(
              "rounded-md px-3 py-1 text-xs font-medium transition-colors",
              billing === "billed"
                ? "bg-[var(--primary)] text-white shadow-sm"
                : "text-[var(--muted-foreground)] hover:text-foreground"
            )}
          >
            Billed
          </button>
        </div>
      </CardHeader>
      <CardContent className="pt-0">
        {filtered.length === 0 ? (
          <p className="text-center text-sm text-[var(--muted-foreground)] py-8">
            No {billing} entries.
          </p>
        ) : (
          <div>
            {filtered.map((e) => {
              const mins = calcMinutes(e);
              const isRunning = Boolean(e.startedAt && !e.endedAt && e.manualMinutes == null);
              const primary = e.userName || e.userEmail;
              const subtitle = `${formatDate(displayWorkDate(e))} · ${subtitlePrefix}`;
              const durationLabel = isRunning
                ? ""
                : mins !== null
                  ? formatMinutes(mins)
                  : "—";
              return (
                <TimeEntryRow
                  key={e.id}
                  accentKey={e.userId}
                  primaryText={primary}
                  subtitle={subtitle}
                  description={e.description}
                  durationLabel={durationLabel}
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
