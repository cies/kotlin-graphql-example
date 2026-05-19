"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import type { Currency, PaymentMethod } from "@prisma/client";

const baseSchema = z.object({
  method: z.enum(["BANK_TRANSFER", "MANUAL", "CASH"]),
  paidAt: z.string().optional(),
  notes: z.string().optional(),
});

const makeSchema = (maxAmount: number) =>
  baseSchema.extend({
    amount: z
      .string()
      .min(1, "Amount required")
      .refine((v) => !isNaN(parseFloat(v)) && parseFloat(v) > 0, "Must be a positive amount")
      .refine(
        (v) => parseFloat(v) <= maxAmount + 0.001,
        `Cannot exceed outstanding balance of ${maxAmount.toFixed(2)}`
      ),
  });

type FormValues = z.infer<ReturnType<typeof makeSchema>>;

interface Props {
  invoiceId: string;
  currency: Currency;
  amountDue: number;
  onRecord: (data: {
    invoiceId: string;
    amount: string;
    currency: Currency;
    method: PaymentMethod;
    paidAt?: string;
    notes?: string;
  }) => Promise<{ error?: string }>;
}

export function PaymentForm({ invoiceId, currency, amountDue, onRecord }: Props) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const schema = makeSchema(amountDue);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
    reset,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      amount: amountDue.toFixed(2),
      method: "BANK_TRANSFER",
      paidAt: new Date().toISOString().split("T")[0],
    },
  });

  const onSubmit = async (data: FormValues) => {
    setError(null);
    const result = await onRecord({
      invoiceId,
      amount: data.amount,
      currency,
      method: data.method as PaymentMethod,
      paidAt: data.paidAt,
      notes: data.notes,
    });

    if (result.error) {
      setError(result.error);
    } else {
      reset();
      setOpen(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">Record Payment</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Record Payment</DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4 mt-2">
          {error && (
            <div className="rounded-md bg-red-50 border border-red-200 p-3 text-red-800 text-sm">
              {error}
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="amount">Amount ({currency})</Label>
            <Input id="amount" type="number" step="0.01" min="0.01" max={amountDue.toFixed(2)} {...register("amount")} />
            {errors.amount && <p className="text-xs text-red-600">{errors.amount.message}</p>}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="method">Payment Method</Label>
            <select
              id="method"
              {...register("method")}
              className="w-full h-10 rounded-md border border-[var(--border)] bg-[var(--background)] px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ring)]"
            >
              <option value="BANK_TRANSFER">Bank Transfer</option>
              <option value="CASH">Cash</option>
              <option value="MANUAL">Other (Manual)</option>
            </select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="paidAt">Payment Date</Label>
            <Input id="paidAt" type="date" {...register("paidAt")} />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="notes">Notes</Label>
            <Textarea id="notes" placeholder="Reference number, notes..." rows={2} {...register("notes")} />
          </div>

          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? "Recording..." : "Record Payment"}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
