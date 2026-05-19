"use client";

import { useForm, useFieldArray, Controller, type SubmitHandler } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Trash2, Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { computeAutoBilledPeriodFromIssueDate } from "@/lib/invoices/auto-billed-period-from-issue";
import { useRouter } from "next/navigation";
import type { Currency } from "@prisma/client";
import {
  formatCurrency,
  formatDate,
  normalizeNumberFormatStyle,
  type NumberFormatStyle,
} from "@/lib/utils/format";
import type { RecurringRuleTemplateDataInput } from "@/lib/invoices/recurring-template-schema";

const QTY_TYPES = [
  { value: "QTY", label: "Qty" },
  { value: "HOURS", label: "Hours" },
  { value: "QTY_HOURS", label: "Qty / Hrs" },
] as const;

const lineSchema = z.object({
  name: z.string().min(1, "Item name required"),
  description: z.string().optional(),
  quantity: z.string().min(1, "Required"),
  qtyType: z.enum(["QTY", "HOURS", "QTY_HOURS"]),
  unitPrice: z.string().min(1, "Required"),
  sortOrder: z.number().optional(),
});

const formSchema = z.object({
  currency: z.string().min(1),
  daysUntilDue: z.number().int().min(0).max(3650),
  vatRate: z.string().optional(),
  vatIncluded: z.boolean().optional(),
  notes: z.string().optional(),
  termsAndConditions: z.string().optional(),
  paymentMethod: z.string().optional(),
  template: z.string().optional(),
  discountType: z.enum(["NONE", "PERCENTAGE", "FIXED"]),
  discountValue: z.string().optional(),
  discountBeforeTax: z.boolean(),
  billedPeriodMode: z.enum(["NONE", "AUTO_BY_ISSUE_DATE"]),
  lines: z.array(lineSchema).min(1, "At least one line item required"),
});

type FormValues = z.infer<typeof formSchema>;

const CURRENCIES = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const;

const PAYMENT_METHODS = [
  { value: "STRIPE", label: "Stripe" },
  { value: "BANK_TRANSFER", label: "Bank Transfer" },
  { value: "MANUAL", label: "Manual" },
  { value: "CASH", label: "Cash" },
] as const;

const NO_PAYMENT_METHOD_VALUE = "__NO_PAYMENT_METHOD__";

const INVOICE_TEMPLATES = [
  { value: "CLASSIC", label: "Classic" },
  { value: "MODERN", label: "Modern" },
  { value: "MINIMAL", label: "Minimal" },
  { value: "CORPORATE", label: "Corporate" },
] as const;

const CURRENCY_SYMBOL: Record<string, string> = {
  EUR: "€",
  USD: "$",
  GBP: "£",
  CHF: "CHF",
  CAD: "CA$",
  AUD: "A$",
};

function toFormDefaults(
  data: RecurringRuleTemplateDataInput,
  orgVatRateDecimal: string
): FormValues {
  const vatDec = data.vatRate != null && data.vatRate !== ""
    ? parseFloat(data.vatRate)
    : parseFloat(orgVatRateDecimal);
  const vatDisplay = Number.isFinite(vatDec) ? (vatDec * 100).toFixed(0) : "0";

  return {
    currency: data.currency,
    daysUntilDue: data.daysUntilDue,
    vatRate: vatDisplay,
    vatIncluded: data.vatIncluded,
    notes: data.notes ?? "",
    termsAndConditions: data.termsAndConditions ?? "",
    paymentMethod: data.paymentMethod ?? NO_PAYMENT_METHOD_VALUE,
    template: data.template ?? "CLASSIC",
    discountType: data.discountType,
    discountValue: data.discountValue ?? "",
    discountBeforeTax: data.discountBeforeTax,
    billedPeriodMode: data.billedPeriodMode ?? "NONE",
    lines: data.lines.map((l, i) => ({
      name: l.name,
      description: l.description ?? "",
      quantity: l.quantity,
      qtyType: l.qtyType ?? "QTY",
      unitPrice: l.unitPrice,
      sortOrder: l.sortOrder ?? i,
    })),
  };
}

interface Props {
  orgSlug: string;
  ruleId: string;
  customerLabel: string;
  initialTemplate: RecurringRuleTemplateDataInput;
  orgVatRate?: string;
  orgDefaultVatIncluded?: boolean;
  orgDefaultTemplate?: string;
  numberFormatStyle?: NumberFormatStyle;
  onSubmit: (data: RecurringRuleTemplateDataInput) => Promise<{ error?: string }>;
  successHref?: string;
  /** Schedule interval; auto billed period applies only when MONTHLY. */
  ruleInterval: string;
  /** When the worker will next create an invoice (ISO), for billed-period preview. */
  nextRunAt: string;
}

export function RecurringInvoiceTemplateForm({
  orgSlug: _orgSlug,
  ruleId: _ruleId,
  ruleInterval,
  nextRunAt,
  customerLabel,
  initialTemplate,
  orgVatRate = "0",
  orgDefaultVatIncluded = false,
  orgDefaultTemplate = "CLASSIC",
  numberFormatStyle = "COMMA_DOT",
  onSubmit,
  successHref,
}: Props) {
  const router = useRouter();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const formDefaults = useMemo(
    () => toFormDefaults(initialTemplate, orgVatRate),
    [initialTemplate, orgVatRate]
  );

  const {
    register,
    control,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: formDefaults,
  });

  const { fields, append, remove } = useFieldArray({ control, name: "lines" });

  const watchedCurrency = watch("currency");
  const watchedVatRate = watch("vatRate");
  const watchedVatIncluded = watch("vatIncluded");
  const watchedLines = watch("lines");
  const watchedDiscountType = watch("discountType");
  const watchedDiscountValue = watch("discountValue");
  const watchedDiscountBeforeTax = watch("discountBeforeTax");
  const watchedBilledPeriodMode = watch("billedPeriodMode");

  const nextRunBilledPeriodPreview = useMemo(() => {
    if (ruleInterval !== "MONTHLY" || watchedBilledPeriodMode !== "AUTO_BY_ISSUE_DATE") return null;
    const d = new Date(nextRunAt);
    if (Number.isNaN(d.getTime())) return null;
    return computeAutoBilledPeriodFromIssueDate(d);
  }, [ruleInterval, watchedBilledPeriodMode, nextRunAt]);

  const vatDecimal = parseFloat(watchedVatRate || "0") / 100;
  const sym = CURRENCY_SYMBOL[watchedCurrency] ?? watchedCurrency;

  function fmtAmt(value: number): string {
    return formatCurrency(
      value,
      (watchedCurrency as Currency) || "EUR",
      normalizeNumberFormatStyle(numberFormatStyle)
    );
  }

  const lineSum = watchedLines.reduce((sum, l) => {
    const qty = parseFloat(l.quantity || "0");
    const rate = parseFloat(l.unitPrice || "0");
    return sum + (isNaN(qty * rate) ? 0 : qty * rate);
  }, 0);

  const discountVal = parseFloat(watchedDiscountValue || "0") || 0;
  const discountBefore = watchedDiscountBeforeTax !== false;
  let discountAmount = 0;
  if (watchedDiscountType === "PERCENTAGE") {
    discountAmount = discountBefore ? lineSum * (discountVal / 100) : 0;
  } else if (watchedDiscountType === "FIXED") {
    discountAmount = discountBefore ? Math.min(discountVal, lineSum) : 0;
  }

  let vatAmount: number, total: number, afterTaxDiscount = 0;
  if (watchedVatIncluded && vatDecimal > 0) {
    const taxable = discountBefore ? lineSum - discountAmount : lineSum;
    total = taxable;
    vatAmount = (total * vatDecimal) / (1 + vatDecimal);
    if (!discountBefore && watchedDiscountType !== "NONE") {
      afterTaxDiscount =
        watchedDiscountType === "PERCENTAGE" ? total * (discountVal / 100) : Math.min(discountVal, total);
      total = total - afterTaxDiscount;
    }
  } else {
    const taxable = discountBefore ? lineSum - discountAmount : lineSum;
    vatAmount = taxable * vatDecimal;
    total = taxable + vatAmount;
    if (!discountBefore && watchedDiscountType !== "NONE") {
      afterTaxDiscount =
        watchedDiscountType === "PERCENTAGE" ? total * (discountVal / 100) : Math.min(discountVal, total);
      total = total - afterTaxDiscount;
    }
  }
  const totalDiscountAmount = discountBefore ? discountAmount : afterTaxDiscount;

  const handleFormSubmit: SubmitHandler<FormValues> = async (data) => {
    setIsSubmitting(true);
    setError(null);
    try {
      const payload: RecurringRuleTemplateDataInput = {
        currency: data.currency as Currency,
        daysUntilDue: data.daysUntilDue,
        vatRate: (parseFloat(data.vatRate || "0") / 100).toString(),
        vatIncluded: data.vatIncluded ?? orgDefaultVatIncluded,
        notes: data.notes || undefined,
        termsAndConditions: data.termsAndConditions || undefined,
        paymentMethod:
          data.paymentMethod && data.paymentMethod !== NO_PAYMENT_METHOD_VALUE
            ? (data.paymentMethod as RecurringRuleTemplateDataInput["paymentMethod"])
            : undefined,
        template: (data.template || orgDefaultTemplate) as RecurringRuleTemplateDataInput["template"],
        discountType: data.discountType,
        discountValue: data.discountValue || undefined,
        discountBeforeTax: data.discountBeforeTax,
        billedPeriodMode: ruleInterval === "MONTHLY" ? data.billedPeriodMode : "NONE",
        lines: data.lines.map((l, i) => ({
          name: l.name,
          description: l.description || undefined,
          quantity: l.quantity,
          qtyType: l.qtyType,
          unitPrice: l.unitPrice,
          sortOrder: l.sortOrder ?? i,
        })),
      };
      const res = await onSubmit(payload);
      if (res?.error) {
        setError(res.error);
        return;
      }
      if (successHref) router.push(successHref);
    } catch (e) {
      setError(e instanceof Error ? e.message : "An error occurred");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit(handleFormSubmit)}
      className="space-y-0 rounded-lg border border-[var(--border)] bg-[var(--card)] px-6 py-8"
    >
      {error && (
        <div className="mb-6 rounded-md bg-red-50 border border-red-200 p-4 text-red-800 text-sm">
          {error}
        </div>
      )}

      <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-2">
        Customer
      </p>
      <p className="text-sm font-medium mb-6">{customerLabel}</p>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <div className="space-y-1.5">
          <Label>Currency</Label>
          <Controller
            control={control}
            name="currency"
            render={({ field }) => (
              <Select value={field.value} onValueChange={field.onChange}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CURRENCIES.map((c) => (
                    <SelectItem key={c} value={c}>
                      {c}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="daysUntilDue">Due in (days from issue)</Label>
          <Input
            id="daysUntilDue"
            type="number"
            min={0}
            max={3650}
            {...register("daysUntilDue", { valueAsNumber: true })}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="vatRate">Tax / VAT (%)</Label>
          <Input id="vatRate" type="number" step="0.1" min={0} max={100} {...register("vatRate")} />
        </div>
        <div className="space-y-1.5">
          <Label>PDF template</Label>
          <Controller
            control={control}
            name="template"
            render={({ field }) => (
              <Select value={field.value ?? "CLASSIC"} onValueChange={field.onChange}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {INVOICE_TEMPLATES.map((t) => (
                    <SelectItem key={t.value} value={t.value}>
                      {t.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </div>
      </div>

      {ruleInterval === "MONTHLY" && (
        <div className="mb-8 rounded-md border border-[var(--border)] bg-[var(--muted)]/20 px-4 py-3">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="text-sm font-medium">Auto billed period</p>
              <p className="text-xs text-[var(--muted-foreground)] mt-1 leading-relaxed">
                Each run sets the invoice period from the issue date (UTC): issued on the 1st → full
                previous calendar month; any other day → one calendar month ending the day before issue.
              </p>
            </div>
            <Controller
              control={control}
              name="billedPeriodMode"
              render={({ field }) => (
                <Switch
                  className="shrink-0"
                  checked={field.value === "AUTO_BY_ISSUE_DATE"}
                  onCheckedChange={(on) =>
                    field.onChange(on ? "AUTO_BY_ISSUE_DATE" : "NONE")
                  }
                />
              )}
            />
          </div>
          {nextRunBilledPeriodPreview ? (
            <p className="text-xs text-[var(--muted-foreground)] mt-3 pt-3 border-t border-[var(--border)] leading-relaxed">
              <span className="font-medium text-[var(--foreground)]">Next run</span> (
              {formatDate(nextRunAt)}) -{" "}
              <span className="font-medium text-[var(--foreground)]">Billed period</span>:{" "}
              <span className="font-medium text-[var(--foreground)] tabular-nums">
                {formatDate(nextRunBilledPeriodPreview.periodFrom)} –{" "}
                {formatDate(nextRunBilledPeriodPreview.periodTo)}
              </span>
            </p>
          ) : (
            <p className="text-xs text-[var(--muted-foreground)] mt-3 pt-3 border-t border-[var(--border)] leading-relaxed">
              <span className="font-medium text-[var(--foreground)]">Next run</span> (
              {formatDate(nextRunAt)}). Turn on auto billed period to preview the billed date range for
              that invoice.
            </p>
          )}
        </div>
      )}

      {vatDecimal > 0 && (
        <div className="flex items-center gap-2 mb-6">
          <input
            id="vatIncluded"
            type="checkbox"
            className="h-3.5 w-3.5 rounded border-[var(--border)] accent-[var(--primary)] cursor-pointer"
            {...register("vatIncluded")}
          />
          <label htmlFor="vatIncluded" className="text-xs text-[var(--muted-foreground)] cursor-pointer">
            Prices include VAT
          </label>
        </div>
      )}

      <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-4">
        Line items
      </p>
      <div className="hidden sm:grid grid-cols-[1fr_96px_108px_96px_36px] gap-2 text-[10px] font-semibold uppercase tracking-wide text-[var(--muted-foreground)] px-1 mb-2">
        <span>Item / Service</span>
        <span className="text-right">Qty</span>
        <span className="text-right">Rate</span>
        <span className="text-right">Amount</span>
        <span />
      </div>

      <div className="space-y-2 mb-6">
        {fields.map((field, index) => {
          const qty = parseFloat(watchedLines[index]?.quantity || "0");
          const rate = parseFloat(watchedLines[index]?.unitPrice || "0");
          const lineTotal = isNaN(qty * rate) ? 0 : qty * rate;
          return (
            <div
              key={field.id}
              className="grid grid-cols-[1fr_96px_108px_96px_36px] gap-2 items-start"
            >
              <div className="space-y-1">
                <Input placeholder="Item name" {...register(`lines.${index}.name`)} />
                {errors.lines?.[index]?.name && (
                  <p className="text-xs text-red-600">{errors.lines[index]?.name?.message}</p>
                )}
                <Input
                  placeholder="Description (optional)"
                  className="text-xs text-[var(--muted-foreground)]"
                  {...register(`lines.${index}.description`)}
                />
              </div>
              <div className="flex flex-col gap-1">
                <Input
                  type="number"
                  step="0.01"
                  min={0}
                  className="text-right tabular-nums"
                  {...register(`lines.${index}.quantity`)}
                />
                <Controller
                  control={control}
                  name={`lines.${index}.qtyType`}
                  render={({ field: f }) => (
                    <Select value={f.value} onValueChange={f.onChange}>
                      <SelectTrigger className="h-7 text-[10px] px-1.5">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {QTY_TYPES.map((t) => (
                          <SelectItem key={t.value} value={t.value} className="text-xs">
                            {t.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>
              <div className="relative">
                <span className="absolute left-2.5 top-[9px] text-xs text-[var(--muted-foreground)] pointer-events-none">
                  {sym}
                </span>
                <Input
                  type="number"
                  step="0.01"
                  min={0}
                  className="pl-6 text-right tabular-nums"
                  {...register(`lines.${index}.unitPrice`)}
                />
              </div>
              <div className="h-10 flex items-center justify-end">
                <span className="text-sm font-medium tabular-nums">{fmtAmt(lineTotal)}</span>
              </div>
              <button
                type="button"
                onClick={() => remove(index)}
                disabled={fields.length === 1}
                className="mt-1 h-8 w-8 flex items-center justify-center rounded-md text-[var(--muted-foreground)] hover:text-red-600 disabled:opacity-30"
                aria-label="Remove line"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          );
        })}
      </div>

      {errors.lines?.root && (
        <p className="text-sm text-red-600 mb-4">{errors.lines.root.message}</p>
      )}

      <div className="flex flex-wrap gap-4 justify-between mb-8">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() =>
            append({
              name: "",
              description: "",
              quantity: "1",
              qtyType: "QTY",
              unitPrice: "0",
              sortOrder: fields.length,
            })
          }
        >
          <Plus className="h-3.5 w-3.5 mr-1" />
          Add item
        </Button>

        <div className="min-w-[260px] space-y-1.5 text-sm">
          <div className="flex items-center gap-2 mb-2">
            <Label className="text-xs text-[var(--muted-foreground)] shrink-0">Discount</Label>
            <Controller
              control={control}
              name="discountType"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger className="h-7 text-xs flex-1">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="NONE">None</SelectItem>
                    <SelectItem value="PERCENTAGE">Percentage (%)</SelectItem>
                    <SelectItem value="FIXED">Fixed amount</SelectItem>
                  </SelectContent>
                </Select>
              )}
            />
            {watchedDiscountType !== "NONE" && (
              <Input
                type="number"
                step="0.01"
                min={0}
                className="h-7 text-xs w-20 text-right tabular-nums"
                {...register("discountValue")}
              />
            )}
            {watchedDiscountType !== "NONE" && (
              <Controller
                control={control}
                name="discountBeforeTax"
                render={({ field }) => (
                  <Select
                    value={field.value ? "before" : "after"}
                    onValueChange={(v) => field.onChange(v === "before")}
                  >
                    <SelectTrigger className="h-7 text-xs w-28">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="before">Before tax</SelectItem>
                      <SelectItem value="after">After tax</SelectItem>
                    </SelectContent>
                  </Select>
                )}
              />
            )}
          </div>
          <div className="flex justify-between gap-10">
            <span className="text-[var(--muted-foreground)]">Sub total</span>
            <span className="tabular-nums font-medium">{fmtAmt(lineSum)}</span>
          </div>
          {watchedDiscountType !== "NONE" && discountBefore && totalDiscountAmount > 0 && (
            <div className="flex justify-between gap-10 text-green-600">
              <span>Discount</span>
              <span className="tabular-nums">-{fmtAmt(totalDiscountAmount)}</span>
            </div>
          )}
          <div className="flex justify-between gap-10">
            <span className="text-[var(--muted-foreground)]">
              VAT ({(vatDecimal * 100).toFixed(1)}%)
            </span>
            <span className="tabular-nums font-medium">{fmtAmt(vatAmount)}</span>
          </div>
          {watchedDiscountType !== "NONE" && !discountBefore && totalDiscountAmount > 0 && (
            <div className="flex justify-between gap-10 text-green-600">
              <span>Discount (after tax)</span>
              <span className="tabular-nums">-{fmtAmt(totalDiscountAmount)}</span>
            </div>
          )}
          <div className="border-t border-[var(--border)] pt-2 flex justify-between gap-10 font-semibold">
            <span>Total</span>
            <span className="tabular-nums">{fmtAmt(total)}</span>
          </div>
        </div>
      </div>

      <div className="space-y-1.5 mb-4">
        <Label>Payment method</Label>
        <Controller
          control={control}
          name="paymentMethod"
          render={({ field }) => (
            <Select value={field.value || NO_PAYMENT_METHOD_VALUE} onValueChange={field.onChange}>
              <SelectTrigger>
                <SelectValue placeholder="None" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_PAYMENT_METHOD_VALUE}>None</SelectItem>
                {PAYMENT_METHODS.map((m) => (
                  <SelectItem key={m.value} value={m.value}>
                    {m.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
        <div className="space-y-1.5">
          <Label>Client notes</Label>
          <Textarea rows={4} {...register("notes")} />
        </div>
        <div className="space-y-1.5">
          <Label>Terms &amp; conditions</Label>
          <Textarea rows={4} {...register("termsAndConditions")} />
        </div>
      </div>

      <div className="flex justify-end">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Saving…" : "Save template"}
        </Button>
      </div>
    </form>
  );
}
