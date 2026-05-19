"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Paperclip, Upload } from "lucide-react";

export type PublicAttachmentRow = {
  id: string;
  filename: string;
  sizeBytes: number;
};

interface Props {
  token: string;
  status: string;
  attachments: PublicAttachmentRow[];
}

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function InvoicePublicAttachments({ token, status, attachments: initial }: Props) {
  const [attachments, setAttachments] = useState(initial);
  const [uploading, setUploading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const canUpload = status !== "VOID" && status !== "DRAFT";

  async function onFiles(files: FileList | null) {
    if (!files?.length) return;
    setUploading(true);
    for (const file of Array.from(files)) {
      const fd = new FormData();
      fd.append("token", token);
      fd.append("file", file);
      try {
        const res = await fetch("/api/uploads/invoices/public", { method: "POST", body: fd });
        const json = (await res.json().catch(() => ({}))) as {
          success?: boolean;
          attachmentId?: string;
          error?: string;
        };
        if (res.ok && json.attachmentId) {
          setAttachments((prev) => [
            ...prev,
            { id: json.attachmentId!, filename: file.name, sizeBytes: file.size },
          ]);
        }
      } catch {
        // ignore
      }
    }
    setUploading(false);
    if (inputRef.current) inputRef.current.value = "";
  }

  if (attachments.length === 0 && !canUpload) return null;

  return (
    <div className="mt-6 pt-4 border-t border-[var(--border)]">
      <div className="flex items-center justify-between gap-2 mb-2">
        <p className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide flex items-center gap-1.5">
          <Paperclip className="h-3.5 w-3.5" />
          Attachments
        </p>
        {canUpload && (
          <>
            <input
              ref={inputRef}
              type="file"
              className="hidden"
              multiple
              onChange={(e) => onFiles(e.target.files)}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-8 text-xs"
              disabled={uploading}
              onClick={() => inputRef.current?.click()}
            >
              <Upload className="h-3.5 w-3.5" />
              {uploading ? "Uploading…" : "Attach files"}
            </Button>
          </>
        )}
      </div>
      {attachments.length === 0 ? (
        <p className="text-sm text-[var(--muted-foreground)]">No files yet.</p>
      ) : (
        <ul className="space-y-1.5 text-sm">
          {attachments.map((a) => (
            <li key={a.id}>
              <a
                href={`/api/uploads/invoices/public?token=${encodeURIComponent(token)}&attachmentId=${encodeURIComponent(a.id)}`}
                className="text-[var(--primary)] hover:underline"
                target="_blank"
                rel="noreferrer"
              >
                {a.filename}
              </a>
              <span className="text-[var(--muted-foreground)] text-xs ml-2">({fmtSize(a.sizeBytes)})</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
