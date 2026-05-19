"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";
import { Pause, Play, Trash2 } from "lucide-react";

interface Props {
  ruleId: string;
  active: boolean;
  onToggle: () => Promise<{ error?: string }>;
  onDelete: () => Promise<{ error?: string }>;
}

export function RecurringRuleActions({ ruleId, active, onToggle, onDelete }: Props) {
  const [loading, setLoading] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const handleToggle = async () => {
    setLoading(true);
    const res = await onToggle();
    setLoading(false);
    if (res?.error) {
      toast.error(res.error);
    } else {
      toast.success(active ? "Rule paused" : "Rule resumed");
    }
  };

  const handleDeleteConfirmed = async () => {
    setDeleteOpen(false);
    setLoading(true);
    const res = await onDelete();
    setLoading(false);
    if (res?.error) {
      toast.error(res.error);
    } else {
      toast.success("Recurring rule deleted");
    }
  };

  return (
    <>
      <div className="flex gap-1">
        <Button variant="ghost" size="icon" className="h-8 w-8" onClick={handleToggle} disabled={loading}>
          {active ? <Pause className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className="h-8 w-8 text-[var(--muted-foreground)] hover:text-red-600"
          onClick={() => setDeleteOpen(true)}
          disabled={loading}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>

      <ConfirmDialog
        open={deleteOpen}
        title="Delete recurring rule?"
        description="This won't affect already-created invoices, but no new invoices will be generated from this rule."
        confirmLabel="Delete"
        onConfirm={handleDeleteConfirmed}
        onCancel={() => setDeleteOpen(false)}
      />
    </>
  );
}
