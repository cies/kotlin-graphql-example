"use client";

import { useState, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Paperclip, Trash2, Download, Upload, Loader2, FileText, Image as ImageIcon } from "lucide-react";
import { deleteTaskAttachment } from "@/lib/actions/tasks";

interface Attachment {
  id: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: string;
  uploader: {
    name: string | null;
    email: string;
  };
}

interface Props {
  orgSlug: string;
  taskId: string;
  attachments: Attachment[];
  readOnly?: boolean;
}

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function AttachmentIcon({ mime }: { mime: string }) {
  if (mime.startsWith("image/")) return <ImageIcon className="h-4 w-4" />;
  return <FileText className="h-4 w-4" />;
}

export function TaskAttachments({ orgSlug, taskId, attachments: initial, readOnly }: Props) {
  const [attachments, setAttachments] = useState(initial);
  const [uploading, setUploading] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function handleUpload(files: FileList | null) {
    if (!files || files.length === 0) return;
    setUploading(true);
    for (const file of Array.from(files)) {
      const fd = new FormData();
      fd.append("file", file);
      try {
        const res = await fetch(`/api/uploads/tasks/${taskId}`, {
          method: "POST",
          body: fd,
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          console.error("Upload failed:", err);
        }
      } catch (e) {
        console.error("Upload error:", e);
      }
    }
    setUploading(false);
    // Refresh the page to show new attachments
    window.location.reload();
  }

  async function handleDelete(attachmentId: string) {
    setDeletingId(attachmentId);
    await deleteTaskAttachment(orgSlug, attachmentId);
    setAttachments((prev) => prev.filter((a) => a.id !== attachmentId));
    setDeletingId(null);
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wide flex items-center gap-2">
          <Paperclip className="h-3.5 w-3.5" />
          Attachments ({attachments.length})
        </h3>
        {!readOnly && (
          <>
            <Button
              size="sm"
              variant="outline"
              onClick={() => fileRef.current?.click()}
              disabled={uploading}
            >
              {uploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
              Upload
            </Button>
            <input
              ref={fileRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => handleUpload(e.target.files)}
            />
          </>
        )}
      </div>

      {attachments.length === 0 && (
        <p className="text-sm text-[var(--muted-foreground)] italic">No attachments yet.</p>
      )}

      <div className="grid grid-cols-1 gap-2">
        {attachments.map((a) => (
          <div
            key={a.id}
            className="flex items-center gap-3 rounded-md border border-[var(--border)] px-3 py-2 text-sm"
          >
            <AttachmentIcon mime={a.mimeType} />
            <div className="flex-1 min-w-0">
              <div className="font-medium truncate">{a.filename}</div>
              <div className="text-xs text-[var(--muted-foreground)]">
                {humanSize(a.sizeBytes)} · {a.uploader.name ?? a.uploader.email}
              </div>
            </div>
            <a
              href={`/api/uploads/tasks/${taskId}?attachmentId=${a.id}`}
              download={a.filename}
              className="text-[var(--muted-foreground)] hover:text-[var(--foreground)] transition-colors"
            >
              <Download className="h-3.5 w-3.5" />
            </a>
            {!readOnly && (
              <button
                className="text-[var(--muted-foreground)] hover:text-red-500 transition-colors"
                onClick={() => handleDelete(a.id)}
                disabled={deletingId === a.id}
              >
                {deletingId === a.id ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Trash2 className="h-3.5 w-3.5" />
                )}
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
