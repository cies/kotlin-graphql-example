"use client";

import { useState, useTransition } from "react";
import { RotateCcw } from "lucide-react";
import { toast } from "sonner";
import type { PaymentMethod } from "@prisma/client";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { refundStripePayment, recordManualPaymentRefund } from "@/lib/actions/payments";

const EPS = 0.001;

type Props = {
  orgSlug: string;
  paymentId: string;
  method: PaymentMethod;
  stripeChargeId: string | null;
  amount: string;
  refundedAmount: string;
};

export function RefundPaymentMenu({
  orgSlug,
  paymentId,
  method,
  stripeChargeId,
  amount,
  refundedAmount,
}: Props) {
  const [pending, startTransition] = useTransition();
  const [partialOpen, setPartialOpen] = useState(false);
  const [partialAmount, setPartialAmount] = useState("");
  const [manualOpen, setManualOpen] = useState(false);
  const [manualAmount, setManualAmount] = useState("");
  const [manualNotes, setManualNotes] = useState("");

  const refundable =
    parseFloat(amount) - parseFloat(refundedAmount || "0");

  const isStripeRefundable =
    method === "STRIPE" && !!stripeChargeId?.trim();

  function runStripeRefund(withAmount?: string) {
    startTransition(async () => {
      const r = await refundStripePayment(orgSlug, paymentId, withAmount?.trim() ? { amount: withAmount.trim() } : {});
      if (r.error) toast.error(r.error);
      else {
        toast.success("Refund submitted");
        setPartialOpen(false);
        setPartialAmount("");
      }
    });
  }

  function runManualRefund() {
    startTransition(async () => {
      const r = await recordManualPaymentRefund(orgSlug, paymentId, {
        amount: manualAmount.trim(),
        notes: manualNotes.trim() || undefined,
      });
      if (r.error) toast.error(r.error);
      else {
        toast.success("Refund recorded");
        setManualOpen(false);
        setManualAmount("");
        setManualNotes("");
      }
    });
  }

  if (refundable <= EPS) return null;

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className="h-8 shrink-0"
            disabled={pending}
            aria-label="Refund payment"
          >
            <RotateCcw className="h-3.5 w-3.5 mr-1" />
            Refund
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          {isStripeRefundable ? (
            <>
              <DropdownMenuItem
                disabled={pending}
                onClick={() => runStripeRefund()}
              >
                Full refund via Stripe
              </DropdownMenuItem>
              <DropdownMenuItem
                disabled={pending}
                onClick={() => {
                  setPartialAmount(refundable.toFixed(2));
                  setPartialOpen(true);
                }}
              >
                Partial refund via Stripe…
              </DropdownMenuItem>
            </>
          ) : (
            <DropdownMenuItem
              disabled={pending}
              onClick={() => {
                setManualAmount(refundable.toFixed(2));
                setManualOpen(true);
              }}
            >
              Record manual refund…
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      <Dialog open={partialOpen} onOpenChange={setPartialOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Partial Stripe refund</DialogTitle>
            <DialogDescription>
              Remaining refundable on this receipt:{" "}
              <span className="font-medium">{refundable.toFixed(2)}</span>
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-2">
            <Label htmlFor="stripe-partial-amt">Amount</Label>
            <Input
              id="stripe-partial-amt"
              value={partialAmount}
              onChange={(e) => setPartialAmount(e.target.value)}
              inputMode="decimal"
            />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPartialOpen(false)}>
              Cancel
            </Button>
            <Button
              type="button"
              disabled={pending || !partialAmount.trim()}
              onClick={() => runStripeRefund(partialAmount)}
            >
              Refund via Stripe
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={manualOpen} onOpenChange={setManualOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Record manual refund</DialogTitle>
            <DialogDescription>
              Use when money was returned outside Stripe (cash, wire, etc.). Max{" "}
              {refundable.toFixed(2)}.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div className="space-y-2">
              <Label htmlFor="manual-refund-amt">Amount</Label>
              <Input
                id="manual-refund-amt"
                value={manualAmount}
                onChange={(e) => setManualAmount(e.target.value)}
                inputMode="decimal"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="manual-refund-notes">Notes (optional)</Label>
              <Input
                id="manual-refund-notes"
                value={manualNotes}
                onChange={(e) => setManualNotes(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setManualOpen(false)}>
              Cancel
            </Button>
            <Button
              type="button"
              disabled={pending || !manualAmount.trim()}
              onClick={() => runManualRefund()}
            >
              Record refund
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
