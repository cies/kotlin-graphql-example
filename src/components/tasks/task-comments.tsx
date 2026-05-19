"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { MessageSquare, Trash2, Send, Loader2 } from "lucide-react";
import { createTaskComment, deleteTaskComment } from "@/lib/actions/tasks";

interface Comment {
  id: string;
  body: string;
  createdAt: string;
  author: {
    id: string;
    name: string | null;
    email: string;
  };
}

interface Props {
  orgSlug: string;
  taskId: string;
  comments: Comment[];
  currentUserId: string;
  isStaff: boolean;
}

export function TaskComments({ orgSlug, taskId, comments: initial, currentUserId, isStaff }: Props) {
  const [comments, setComments] = useState(initial);
  const [body, setBody] = useState("");
  const [posting, setPosting] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function handlePost() {
    if (!body.trim()) return;
    setPosting(true);
    const result = await createTaskComment(orgSlug, taskId, body);
    setPosting(false);
    if ("success" in result) {
      setBody("");
    }
  }

  async function handleDelete(commentId: string) {
    setDeletingId(commentId);
    await deleteTaskComment(orgSlug, commentId);
    setComments((prev) => prev.filter((c) => c.id !== commentId));
    setDeletingId(null);
  }

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wide flex items-center gap-2">
        <MessageSquare className="h-3.5 w-3.5" />
        Comments ({comments.length})
      </h3>

      {comments.length === 0 && (
        <p className="text-sm text-[var(--muted-foreground)] italic">No comments yet. Be the first!</p>
      )}

      <div className="space-y-3">
        {comments.map((c) => (
          <div key={c.id} className="flex gap-3">
            <div className="h-8 w-8 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-xs font-bold text-[var(--primary)] flex-shrink-0">
              {(c.author.name ?? c.author.email)[0]?.toUpperCase()}
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-sm font-semibold">{c.author.name ?? c.author.email}</span>
                <span className="text-xs text-[var(--muted-foreground)] flex-shrink-0">
                  {new Date(c.createdAt).toLocaleString("en-GB", { dateStyle: "short", timeStyle: "short" })}
                </span>
              </div>
              <div className="mt-1 text-sm bg-[var(--muted)]/30 rounded-md px-3 py-2 whitespace-pre-wrap">
                {c.body}
              </div>
            </div>
            {(isStaff || c.author.id === currentUserId) && (
              <button
                className="h-8 w-8 flex items-center justify-center text-[var(--muted-foreground)] hover:text-red-500 transition-colors flex-shrink-0"
                onClick={() => handleDelete(c.id)}
                disabled={deletingId === c.id}
              >
                {deletingId === c.id ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Trash2 className="h-3.5 w-3.5" />
                )}
              </button>
            )}
          </div>
        ))}
      </div>

      {/* New comment */}
      <div className="flex gap-3">
        <div className="h-8 w-8 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-xs font-bold text-[var(--primary)] flex-shrink-0">
          •
        </div>
        <div className="flex-1 space-y-2">
          <Textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="Write a comment…"
            rows={3}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handlePost();
            }}
          />
          <div className="flex justify-end">
            <Button size="sm" onClick={handlePost} disabled={posting || !body.trim()}>
              {posting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
              Post
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
