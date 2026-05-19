"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { createCustomerNote, deleteCustomerNote } from "@/lib/actions/customers";
import { MessageSquare, Trash2, Loader2 } from "lucide-react";
import { formatDate } from "@/lib/utils/format";

interface Note {
  id: string;
  content: string;
  createdAt: Date;
  authorId: string | null;
}

interface Props {
  orgSlug: string;
  customerId: string;
  notes: Note[];
}

export function CustomerNotes({ orgSlug, customerId, notes }: Props) {
  const [content, setContent] = useState("");
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function handleAdd() {
    if (!content.trim()) return;
    setSaving(true);
    await createCustomerNote(orgSlug, customerId, content.trim());
    setContent("");
    setSaving(false);
  }

  async function handleDelete(noteId: string) {
    setDeletingId(noteId);
    await deleteCustomerNote(orgSlug, customerId, noteId);
    setDeletingId(null);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MessageSquare className="h-4 w-4" />
          Notes
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex gap-2">
          <Textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="Add a note…"
            rows={2}
            className="resize-none"
          />
          <Button onClick={handleAdd} disabled={saving || !content.trim()}>
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : "Add"}
          </Button>
        </div>

        <div className="space-y-3">
          {notes.map((note) => (
            <div
              key={note.id}
              className="rounded-md border border-[var(--border)] p-3 text-sm"
            >
              <div className="flex items-start justify-between gap-2">
                <p className="flex-1 whitespace-pre-wrap">{note.content}</p>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
                  onClick={() => handleDelete(note.id)}
                  disabled={deletingId === note.id}
                >
                  {deletingId === note.id ? (
                    <Loader2 className="h-3 w-3 animate-spin" />
                  ) : (
                    <Trash2 className="h-3 w-3" />
                  )}
                </Button>
              </div>
              <p className="text-xs text-[var(--muted-foreground)] mt-1">
                {formatDate(note.createdAt)}
              </p>
            </div>
          ))}
          {notes.length === 0 && (
            <p className="text-sm text-[var(--muted-foreground)]">No notes yet.</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
