"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Loader2, Trash2 } from "lucide-react";
import { deleteContact } from "@/lib/actions/customers";
import { toast } from "sonner";

interface Props {
  orgSlug: string;
  customerId: string;
  contactId: string;
}

export function DeleteContactButton({ orgSlug, customerId, contactId }: Props) {
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  async function handleConfirm() {
    setOpen(false);
    setLoading(true);
    try {
      await deleteContact(orgSlug, customerId, contactId);
      toast.success("Contact deleted");
    } catch {
      toast.error("Failed to delete contact");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <Button
        variant="ghost"
        size="icon"
        className="h-7 w-7 text-[var(--muted-foreground)] hover:text-red-600"
        onClick={() => setOpen(true)}
        disabled={loading}
        aria-label="Delete contact"
      >
        {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
      </Button>

      <ConfirmDialog
        open={open}
        title="Delete contact?"
        description="Their portal account will also be removed. This cannot be undone."
        confirmLabel="Delete"
        onConfirm={handleConfirm}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}
