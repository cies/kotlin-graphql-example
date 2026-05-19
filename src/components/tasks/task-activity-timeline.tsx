"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Send, Loader2, Trash2, Plus, ArrowRight, Users, DollarSign,
  FileText, CheckCircle, RotateCcw, Activity,
} from "lucide-react";
import { createTaskComment, deleteTaskComment } from "@/lib/actions/tasks";

// ─── Types ────────────────────────────────────────────────────────────────────

interface CommentItem {
  kind: "comment";
  id: string;
  body: string;
  createdAt: string;
  author: { id: string; name: string | null; email: string };
}

interface ActivityItem {
  kind: "activity";
  id: string;
  type: string;
  actorName: string | null;
  metadata: Record<string, unknown> | null;
  createdAt: string;
}

type TimelineItem = CommentItem | ActivityItem;

interface Props {
  orgSlug: string;
  taskId: string;
  comments: Omit<CommentItem, "kind">[];
  activities: Omit<ActivityItem, "kind">[];
  currentUserId: string;
  isStaff: boolean;
  memberMap: Record<string, string>; // userId → displayName (for assignee changes)
}

// ─── Activity renderers ───────────────────────────────────────────────────────

const ACTIVITY_ICONS: Record<string, React.ReactNode> = {
  CREATED:             <Plus className="h-3.5 w-3.5" />,
  STATUS_CHANGED:      <ArrowRight className="h-3.5 w-3.5" />,
  TITLE_CHANGED:       <FileText className="h-3.5 w-3.5" />,
  ASSIGNEES_CHANGED:   <Users className="h-3.5 w-3.5" />,
  RATE_CHANGED:        <DollarSign className="h-3.5 w-3.5" />,
  DESCRIPTION_CHANGED: <FileText className="h-3.5 w-3.5" />,
  BILLED:              <CheckCircle className="h-3.5 w-3.5" />,
  UNBILLED:            <RotateCcw className="h-3.5 w-3.5" />,
};

function activityText(
  item: ActivityItem,
  memberMap: Record<string, string>
): React.ReactNode {
  const actor = item.actorName ?? "Someone";
  const m = item.metadata ?? {};

  switch (item.type) {
    case "CREATED":
      return <><strong>{actor}</strong> created this task</>;

    case "STATUS_CHANGED":
      return (
        <>
          <strong>{actor}</strong> moved status from{" "}
          <Badge variant="secondary" className="text-xs px-1 py-0">{String(m.from ?? "").replace("_", " ")}</Badge>
          {" "}to{" "}
          <Badge variant="secondary" className="text-xs px-1 py-0">{String(m.to ?? "").replace("_", " ")}</Badge>
        </>
      );

    case "TITLE_CHANGED":
      return (
        <>
          <strong>{actor}</strong> renamed the task from{" "}
          <span className="italic text-[var(--muted-foreground)]">&ldquo;{String(m.from ?? "")}&rdquo;</span>
          {" "}to{" "}
          <span className="italic">&ldquo;{String(m.to ?? "")}&rdquo;</span>
        </>
      );

    case "ASSIGNEES_CHANGED": {
      const added = (m.added as string[] | undefined) ?? [];
      const removed = (m.removed as string[] | undefined) ?? [];
      const parts: React.ReactNode[] = [];
      if (added.length > 0) {
        const names = added.map((id) => memberMap[id] ?? id).join(", ");
        parts.push(<>assigned <strong>{names}</strong></>);
      }
      if (removed.length > 0) {
        const names = removed.map((id) => memberMap[id] ?? id).join(", ");
        parts.push(<>unassigned <strong>{names}</strong></>);
      }
      return (
        <>
          <strong>{actor}</strong>{" "}
          {parts.map((p, i) => (
            <span key={i}>{p}{i < parts.length - 1 ? " and " : ""}</span>
          ))}
        </>
      );
    }

    case "RATE_CHANGED": {
      const from = m.from != null ? `${m.from}/h` : "none";
      const to = m.to != null ? `${m.to}/h` : "none";
      return (
        <>
          <strong>{actor}</strong> changed hourly rate from{" "}
          <span className="font-mono text-xs">{from}</span> to{" "}
          <span className="font-mono text-xs">{to}</span>
        </>
      );
    }

    case "DESCRIPTION_CHANGED":
      return <><strong>{actor}</strong> updated the description</>;

    case "BILLED":
      return <><strong>{actor}</strong> billed this task on invoice <span className="font-mono text-xs">{String(m.invoiceNumber ?? "")}</span></>;

    case "UNBILLED":
      return <><strong>{actor}</strong> unbilled this task (invoice voided)</>;

    default:
      return <><strong>{actor}</strong> {item.type.toLowerCase().replace("_", " ")}</>;
  }
}

function formatTs(iso: string) {
  return new Date(iso).toLocaleString("en-GB", {
    dateStyle: "short",
    timeStyle: "short",
  });
}

function initials(name: string | null, email: string) {
  const src = name ?? email;
  return src[0]?.toUpperCase() ?? "?";
}

// ─── Component ────────────────────────────────────────────────────────────────

export function TaskActivityTimeline({
  orgSlug, taskId, comments: initialComments, activities,
  currentUserId, isStaff, memberMap,
}: Props) {
  const [comments, setComments] = useState(initialComments);
  const [body, setBody] = useState("");
  const [posting, setPosting] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    setComments(initialComments);
  }, [initialComments]);

  // Merge and sort
  const items: TimelineItem[] = [
    ...activities.map((a) => ({ ...a, kind: "activity" as const })),
    ...comments.map((c) => ({ ...c, kind: "comment" as const })),
  ].sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());

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
    <div className="space-y-1">
      <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wide flex items-center gap-2 mb-4">
        <Activity className="h-3.5 w-3.5" />
        Activity
      </h3>

      <div className="relative">
        {/* Vertical line */}
        <div className="absolute left-3.5 top-0 bottom-0 w-px bg-[var(--border)]" />

        <div className="space-y-0">
          {items.map((item) =>
            item.kind === "activity" ? (
              <div key={item.id} className="flex gap-3 py-2">
                <div className="relative z-10 h-7 w-7 rounded-full bg-[var(--muted)] border border-[var(--border)] flex items-center justify-center flex-shrink-0 text-[var(--muted-foreground)]">
                  {ACTIVITY_ICONS[item.type] ?? <Activity className="h-3.5 w-3.5" />}
                </div>
                <div className="flex-1 min-w-0 pt-1">
                  <p className="text-sm text-[var(--muted-foreground)] leading-relaxed">
                    {activityText(item, memberMap)}
                    <span className="ml-2 text-xs opacity-60">{formatTs(item.createdAt)}</span>
                  </p>
                </div>
              </div>
            ) : (
              <div key={item.id} className="flex gap-3 py-3">
                <div className="relative z-10 h-7 w-7 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-xs font-bold text-[var(--primary)] flex-shrink-0">
                  {initials(item.author.name, item.author.email)}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-semibold">
                      {item.author.name ?? item.author.email}
                    </span>
                    <div className="flex items-center gap-1 flex-shrink-0">
                      <span className="text-xs text-[var(--muted-foreground)]">
                        {formatTs(item.createdAt)}
                      </span>
                      {(isStaff || item.author.id === currentUserId) && (
                        <button
                          className="h-6 w-6 flex items-center justify-center text-[var(--muted-foreground)] hover:text-red-500 transition-colors"
                          onClick={() => handleDelete(item.id)}
                          disabled={deletingId === item.id}
                        >
                          {deletingId === item.id
                            ? <Loader2 className="h-3 w-3 animate-spin" />
                            : <Trash2 className="h-3 w-3" />}
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="mt-1 text-sm bg-[var(--muted)]/30 rounded-md px-3 py-2 whitespace-pre-wrap border border-[var(--border)]">
                    {item.body}
                  </div>
                </div>
              </div>
            )
          )}
        </div>

        {/* Add comment */}
        <div className="flex gap-3 pt-4">
          <div className="relative z-10 h-7 w-7 rounded-full bg-[var(--muted)] flex items-center justify-center flex-shrink-0">
            <span className="text-xs text-[var(--muted-foreground)]">•</span>
          </div>
          <div className="flex-1 space-y-2">
            <Textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="Add a comment…"
              rows={3}
              onKeyDown={(e) => {
                if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handlePost();
              }}
            />
            <div className="flex justify-end">
              <Button size="sm" onClick={handlePost} disabled={posting || !body.trim()}>
                {posting ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <Send className="h-3.5 w-3.5 mr-1" />}
                Post
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
