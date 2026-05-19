"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { deleteInvoiceAttachment } from "@/lib/actions/invoices";
import { Paperclip, Trash2, Loader2 } from "lucide-react";
import type { InvoiceAttachmentUploader } from "@prisma/client";

export type StaffAttachmentRow = {
  id: string;
  filename: string;
  sizeBytes: number;
  uploadedBy: InvoiceAttachmentUploader;
};

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

interface Props {
  orgSlug: string;
  attachments: StaffAttachmentRow[];
}

export function InvoiceStaffAttachments({ orgSlug, attachments: initial }: Props) {
  const [attachments, setAttachments] = useState(initial);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function handleDelete(id: string) {
    setDeletingId(id);
    const res = await deleteInvoiceAttachment(orgSlug, id);
    setDeletingId(null);
    if (!("error" in res && res.error)) {
      setAttachments((prev) => prev.filter((a) => a.id !== id));
    }
  }

  if (attachments.length === 0) {
    return (
      <p className="text-sm text-[var(--muted-foreground)] py-2">
        No attachments. Customers can upload files from the public invoice link.
      </p>
    );
  }

  return (
    <ul className="space-y-2 text-sm">
      {attachments.map((a) => (
        <li
          key={a.id}
          className="flex items-center justify-between gap-2 rounded-md border border-[var(--border)] px-3 py-2"
        >
          <div className="flex items-center gap-2 min-w-0">
            <Paperclip className="h-4 w-4 shrink-0 text-[var(--muted-foreground)]" />
            <a
              href={`/api/uploads/invoices/staff/${a.id}?orgSlug=${encodeURIComponent(orgSlug)}`}
              className="text-[var(--primary)] hover:underline truncate"
              target="_blank"
              rel="noreferrer"
            >
              {a.filename}
            </a>
            <span className="text-[var(--muted-foreground)] text-xs shrink-0">
              ({fmtSize(a.sizeBytes)}) · {a.uploadedBy === "PUBLIC" ? "Customer" : "Staff"}
            </span>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 shrink-0 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
            onClick={() => handleDelete(a.id)}
            disabled={deletingId === a.id}
          >
            {deletingId === a.id ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Trash2 className="h-4 w-4" />
            )}
          </Button>
        </li>
      ))}
    </ul>
  );
}
