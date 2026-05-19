"use client";

import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";

interface Props {
  className?: string;
  action: () => Promise<void>;
}

export function CancelSubscriptionButton({ className, action }: Props) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  async function handleConfirm() {
    setOpen(false);
    setLoading(true);
    try {
      await action();
      toast.success("Subscription cancelled");
    } catch {
      toast.error("Failed to cancel subscription");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <button
        type="button"
        className={className}
        onClick={() => setOpen(true)}
        disabled={loading}
      >
        {loading ? "Cancelling…" : "Cancel"}
      </button>

      <ConfirmDialog
        open={open}
        title="Cancel subscription?"
        description="The subscription will be cancelled immediately. This action cannot be undone."
        confirmLabel="Cancel subscription"
        onConfirm={handleConfirm}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}
