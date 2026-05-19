"use client";

import { useState, useRef } from "react";
import { Loader2, Pencil } from "lucide-react";
import { updateTask } from "@/lib/actions/tasks";

interface Props {
  orgSlug: string;
  projectId: string;
  taskId: string;
  initialTitle: string;
}

export function TaskTitleEdit({ orgSlug, projectId, taskId, initialTitle }: Props) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(initialTitle);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  function startEdit() {
    setEditing(true);
    setTimeout(() => inputRef.current?.select(), 0);
  }

  async function save() {
    const trimmed = title.trim();
    if (!trimmed) {
      setTitle(initialTitle);
      setEditing(false);
      return;
    }
    if (trimmed === initialTitle) {
      setEditing(false);
      return;
    }
    setSaving(true);
    await updateTask(orgSlug, projectId, taskId, { title: trimmed });
    setSaving(false);
    setEditing(false);
  }

  if (editing) {
    return (
      <div className="flex items-center gap-2 flex-1">
        <input
          ref={inputRef}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={save}
          onKeyDown={(e) => {
            if (e.key === "Enter") save();
            if (e.key === "Escape") { setTitle(initialTitle); setEditing(false); }
          }}
          className="text-2xl font-bold bg-transparent border-b-2 border-[var(--primary)] outline-none flex-1 min-w-0"
          autoFocus
        />
        {saving && <Loader2 className="h-4 w-4 animate-spin text-[var(--muted-foreground)] flex-shrink-0" />}
      </div>
    );
  }

  return (
    <button
      onClick={startEdit}
      className="group flex items-center gap-2 text-left"
      title="Click to edit title"
    >
      <h1 className="text-2xl font-bold">{title}</h1>
      <Pencil className="h-4 w-4 text-[var(--muted-foreground)] opacity-0 group-hover:opacity-60 transition-opacity flex-shrink-0" />
    </button>
  );
}
