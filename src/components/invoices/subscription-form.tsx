"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Plus } from "lucide-react";

const schema = z.object({
  productName: z.string().min(1, "Product name required"),
  priceAmount: z.number().positive("Amount must be positive"),
  priceCurrency: z.string().length(3, "3-letter currency code required"),
  interval: z.enum(["month", "year", "week"]),
  intervalCount: z.number().int().positive(),
});

type FormValues = z.infer<typeof schema>;

interface Props {
  customerId: string;
  onCreate: (data: {
    customerId: string;
    productName: string;
    priceAmount: number;
    priceCurrency: string;
    interval: "month" | "year" | "week";
    intervalCount: number;
  }) => Promise<{ error?: string }>;
}

export function SubscriptionForm({ customerId, onCreate }: Props) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
    reset,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      priceCurrency: "EUR",
      interval: "month",
      intervalCount: 1,
    },
  });

  const onSubmit = async (data: FormValues) => {
    setError(null);
    const result = await onCreate({ customerId, ...data });
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
        <Button size="sm" variant="outline">
          <Plus className="h-3.5 w-3.5" />
          New Subscription
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Stripe Subscription</DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4 mt-2">
          {error && (
            <div className="rounded-md bg-red-50 border border-red-200 p-3 text-red-800 text-sm">
              {error}
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="productName">Product / Plan Name</Label>
            <Input id="productName" placeholder="Monthly Retainer" {...register("productName")} />
            {errors.productName && <p className="text-xs text-red-600">{errors.productName.message}</p>}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="priceAmount">Amount</Label>
              <Input
                id="priceAmount"
                type="number"
                step="0.01"
                min="0.01"
                {...register("priceAmount", { valueAsNumber: true })}
              />
              {errors.priceAmount && <p className="text-xs text-red-600">{errors.priceAmount.message}</p>}
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="priceCurrency">Currency</Label>
              <Input id="priceCurrency" maxLength={3} className="uppercase" {...register("priceCurrency")} />
              {errors.priceCurrency && <p className="text-xs text-red-600">{errors.priceCurrency.message}</p>}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="interval">Billing Interval</Label>
              <select
                id="interval"
                {...register("interval")}
                className="w-full h-10 rounded-md border border-[var(--border)] bg-[var(--background)] px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ring)]"
              >
                <option value="month">Monthly</option>
                <option value="year">Yearly</option>
                <option value="week">Weekly</option>
              </select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="intervalCount">Every N intervals</Label>
              <Input
                id="intervalCount"
                type="number"
                min="1"
                {...register("intervalCount", { valueAsNumber: true })}
              />
            </div>
          </div>

          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? "Creating..." : "Create Subscription"}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
