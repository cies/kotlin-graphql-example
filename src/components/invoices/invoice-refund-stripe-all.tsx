"use client";

import { useState, useTransition } from "react";
import { RotateCcw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { refundAllStripePaymentsOnInvoice } from "@/lib/actions/payments";

export function InvoiceRefundAllStripeButton({
  orgSlug,
  invoiceId,
  disabled,
}: {
  orgSlug: string;
  invoiceId: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();

  const confirm = () => {
    startTransition(async () => {
      const r = await refundAllStripePaymentsOnInvoice(orgSlug, invoiceId);
      setOpen(false);
      if (r.error) toast.error(r.error);
      else toast.success("Stripe refunds submitted for all card payments");
    });
  };

  return (
    <>
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={disabled || pending}
        onClick={() => setOpen(true)}
      >
        <RotateCcw className="h-4 w-4" />
        {pending ? "Refunding…" : "Refund all (Stripe)"}
      </Button>
      <ConfirmDialog
        open={open}
        title="Refund all Stripe charges?"
        description="This creates a full refund in Stripe for each card payment on this invoice. Partially refunded charges are refunded up to the remaining amount. You can also refund individual payments in the table below."
        confirmLabel="Refund all"
        onConfirm={confirm}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}
