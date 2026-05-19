"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Pencil, Check, X } from "lucide-react";
import { editTaskDescription } from "@/lib/actions/tasks";

interface Props {
  orgSlug: string;
  taskId: string;
  description: string | null;
  readOnly?: boolean;
}

export function TaskDescription({ orgSlug, taskId, description, readOnly }: Props) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(description ?? "");
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    await editTaskDescription(orgSlug, taskId, value);
    setSaving(false);
    setEditing(false);
  }

  function handleCancel() {
    setValue(description ?? "");
    setEditing(false);
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wide">Description</h3>
        {!readOnly && !editing && (
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => setEditing(true)}>
            <Pencil className="h-3.5 w-3.5" />
          </Button>
        )}
      </div>
      {editing ? (
        <div className="space-y-2">
          <Textarea
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="Describe this task..."
            rows={5}
            autoFocus
          />
          <div className="flex gap-2">
            <Button size="sm" onClick={handleSave} disabled={saving}>
              <Check className="h-3.5 w-3.5 mr-1" />
              Save
            </Button>
            <Button size="sm" variant="ghost" onClick={handleCancel}>
              <X className="h-3.5 w-3.5 mr-1" />
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div
          className={`min-h-[60px] rounded-md text-sm whitespace-pre-wrap ${
            description
              ? "text-[var(--foreground)]"
              : "text-[var(--muted-foreground)] italic"
          } ${!readOnly ? "cursor-pointer hover:bg-[var(--muted)]/30 px-1 py-1 -mx-1 rounded transition-colors" : ""}`}
          onClick={!readOnly ? () => setEditing(true) : undefined}
        >
          {description || "Click to add a description…"}
        </div>
      )}
    </div>
  );
}
