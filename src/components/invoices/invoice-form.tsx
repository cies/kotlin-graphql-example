"use client";

import { useForm, useFieldArray, Controller, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Trash2, Plus, RefreshCw, FileDown, ExternalLink, ClipboardList } from "lucide-react";
import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import type { Currency } from "@prisma/client";
import { getBillableTasks, type BillableTask } from "@/lib/actions/invoices";
import { computeAutoBilledPeriodFromIssueDate } from "@/lib/invoices/auto-billed-period-from-issue";
import {
  formatCurrency,
  formatDate,
  normalizeNumberFormatStyle,
  type NumberFormatStyle,
} from "@/lib/utils/format";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils/cn";
import { previewInvoiceDocNumber } from "@/lib/invoices/number-preview";

// ─── Schema ──────────────────────────────────────────────────────────────────

const QTY_TYPES = [
  { value: "QTY", label: "Qty" },
  { value: "HOURS", label: "Hours" },
  { value: "QTY_HOURS", label: "Qty / Hrs" },
] as const;

const lineSchema = z.object({
  name: z.string().min(1, "Item name required"),
  description: z.string().optional(),
  quantity: z.string().min(1, "Required"),
  qtyType: z.enum(["QTY", "HOURS", "QTY_HOURS"]).default("QTY"),
  unitPrice: z.string().min(1, "Required"),
  sortOrder: z.number().optional(),
  taskId: z.string().optional(),
});

const invoiceFormSchema = z.object({
  customerId: z.string().optional(),
  currency: z.string().min(1),
  dueDate: z.string().optional(),
  issuedAt: z.string().optional(),
  vatRate: z.string().optional(),
  vatIncluded: z.boolean().optional(),
  periodFrom: z.string().optional(),
  periodTo: z.string().optional(),
  notes: z.string().optional(),
  adminNote: z.string().optional(),
  termsAndConditions: z.string().optional(),
  paymentMethod: z.string().optional(),
  template: z.string().optional(),
  discountType: z.enum(["NONE", "PERCENTAGE", "FIXED"]).default("NONE"),
  discountValue: z.string().optional(),
  discountBeforeTax: z.boolean().default(true),
  recurring: z.boolean().optional(),
  recurringInterval: z.enum(["WEEKLY", "BIWEEKLY", "MONTHLY"]).optional(),
  recurringStartDate: z.string().optional(),
  recurringEndDate: z.string().optional(),
  recurringBilledPeriodMode: z.enum(["NONE", "AUTO_BY_ISSUE_DATE"]).default("NONE"),
  lines: z.array(lineSchema).min(1, "At least one line item required"),
  invoiceNumberPrefixOverride: z.string().max(200).optional(),
});

type InvoiceFormValues = z.infer<typeof invoiceFormSchema>;

export type { InvoiceFormValues };

interface Customer {
  id: string;
  label: string;
  preferredCurrency: Currency;
  email?: string | null;
  vat?: string | null;
  billingAddress?: unknown;
}

interface Props {
  customers: Customer[];
  /** org-level VAT rate as decimal string, e.g. "0.20" for 20% */
  orgVatRate?: string;
  /** org-level default for whether prices include VAT */
  orgDefaultVatIncluded?: boolean;
  defaultCurrency?: Currency;
  /** org-level default invoice template, used when creating new invoices */
  orgDefaultTemplate?: string;
  /** org-level default due days (null = manual) */
  orgDefaultDueDays?: number | null;
  numberFormatStyle?: NumberFormatStyle;
  /** When set, used with optional prefix override to preview the next invoice number (new invoice only). */
  orgInvoiceNumbering?: {
    invoiceNumberPrefix: string;
    invoiceNumberFormat: string;
    invoiceNumberPadding: number;
    invoiceNumberRandomLength: number;
  } | null;
  onSubmit: (data: InvoiceFormValues) => Promise<void>;
  submitLabel?: string;
  defaultValues?: Partial<InvoiceFormValues>;
  orgSlug: string;
  invoiceId?: string;
  invoiceNumber?: string;
  /** When set, Bill Task Hours lists only tasks for this project (same customer). */
  projectId?: string | null;
}

// ─── Constants ───────────────────────────────────────────────────────────────

const CURRENCIES = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const;

const PAYMENT_METHODS = [
  { value: "STRIPE", label: "Stripe" },
  { value: "BANK_TRANSFER", label: "Bank Transfer" },
  { value: "MANUAL", label: "Manual" },
  { value: "CASH", label: "Cash" },
] as const;

const NO_CUSTOMER_VALUE = "__NO_CUSTOMER__";
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

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatBillingAddress(address: unknown): string[] {
  if (!address || typeof address !== "object" || Array.isArray(address)) return [];
  const a = address as Record<string, unknown>;

  const street =
    (a.line1 ?? a.street ?? a.address1 ?? a.address) as string | undefined;
  const street2 = (a.line2 ?? a.address2) as string | undefined;
  const city = a.city ? String(a.city).trim() : "";
  const state = a.state ?? a.province;
  const postal = a.postalCode ?? a.zip ?? a.postal_code;
  const country = a.country ? String(a.country).trim() : "";

  const locality = [city, state, country, postal]
    .map((v) => (v != null && v !== "" ? String(v).trim() : ""))
    .filter(Boolean)
    .join(", ");

  const lines = [street, street2, locality || undefined].filter((v): v is string => !!v);

  if (lines.length > 0) return lines;

  // Fallback: show non-object leaf values
  return Object.values(a)
    .filter((v) => v && typeof v !== "object")
    .map(String)
    .slice(0, 4);
}

// ─── Component ───────────────────────────────────────────────────────────────

export function InvoiceForm({
  customers,
  orgVatRate = "0",
  orgDefaultVatIncluded = false,
  defaultCurrency = "EUR",
  orgDefaultTemplate = "CLASSIC",
  orgDefaultDueDays,
  numberFormatStyle = "COMMA_DOT",
  orgInvoiceNumbering,
  onSubmit,
  submitLabel = "Save Draft",
  defaultValues,
  orgSlug,
  invoiceId,
  invoiceNumber,
  projectId: billableTasksProjectId,
}: Props) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Bill Task dialog
  const [billTaskOpen, setBillTaskOpen] = useState(false);
  const [billableTasks, setBillableTasks] = useState<BillableTask[]>([]);
  const [billTaskLoading, setBillTaskLoading] = useState(false);
  const [selectedTaskIds, setSelectedTaskIds] = useState<Set<string>>(new Set());

  // Live preview
  const [previewBlobUrl, setPreviewBlobUrl] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState(false);
  const blobUrlRef = useRef<string | null>(null);

  // orgVatRate arrives as decimal (e.g. "0.20"); show as percentage "20"
  const initialVatDisplay = orgVatRate
    ? (parseFloat(orgVatRate) * 100).toFixed(0)
    : "0";

  const {
    register,
    control,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<InvoiceFormValues>({
    resolver: zodResolver(invoiceFormSchema) as Resolver<InvoiceFormValues>,
    defaultValues: {
      customerId: "",
      currency: defaultCurrency,
      vatRate: initialVatDisplay,
      vatIncluded: orgDefaultVatIncluded,
      issuedAt: new Date().toISOString().split("T")[0],
      dueDate: orgDefaultDueDays != null
        ? new Date(Date.now() + orgDefaultDueDays * 86_400_000).toISOString().split("T")[0]
        : undefined,
      paymentMethod: "",
      template: orgDefaultTemplate,
      discountType: "NONE" as const,
      discountValue: "",
      discountBeforeTax: true,
      recurring: false,
      recurringInterval: "MONTHLY" as const,
      recurringBilledPeriodMode: "NONE" as const,
      invoiceNumberPrefixOverride: "",
      lines: [{ name: "", description: "", quantity: "1", qtyType: "QTY" as const, unitPrice: "0", sortOrder: 0, taskId: undefined }],
      ...defaultValues,
    },
  });

  const { fields, append, remove } = useFieldArray({ control, name: "lines" });

  const watchedCustomerId = watch("customerId");
  const watchedCurrency = watch("currency");
  const watchedVatRate = watch("vatRate");
  const watchedVatIncluded = watch("vatIncluded");
  const watchedDueDate = watch("dueDate");
  const watchedIssuedAt = watch("issuedAt");
  const watchedPeriodFrom = watch("periodFrom");
  const watchedPeriodTo = watch("periodTo");
  const watchedNotes = watch("notes");
  const watchedTermsAndConditions = watch("termsAndConditions");
  const watchedTemplate = watch("template");
  const watchedLines = watch("lines");
  const watchedDiscountType = watch("discountType");
  const watchedDiscountValue = watch("discountValue");
  const watchedDiscountBeforeTax = watch("discountBeforeTax");
  const watchedRecurring = watch("recurring");
  const watchedRecurringInterval = watch("recurringInterval");
  const watchedRecurringBilledPeriodMode = watch("recurringBilledPeriodMode");
  const watchedRecurringStartDate = watch("recurringStartDate");
  const watchedInvoiceNumberPrefixOverride = watch("invoiceNumberPrefixOverride");
  const selectedTaskIdsInLines = useMemo(
    () =>
      new Set(
        watchedLines
          .map((line) => line.taskId)
          .filter((taskId): taskId is string => Boolean(taskId))
      ),
    [watchedLines]
  );

  // Set currency to customer preference on selection
  useEffect(() => {
    const customer = customers.find((c) => c.id === watchedCustomerId);
    if (customer && !defaultValues?.currency) {
      setValue("currency", customer.preferredCurrency);
    }
  }, [watchedCustomerId, customers, setValue, defaultValues]);

  useEffect(() => {
    if (
      watchedRecurringInterval !== "MONTHLY" &&
      watchedRecurringBilledPeriodMode === "AUTO_BY_ISSUE_DATE"
    ) {
      setValue("recurringBilledPeriodMode", "NONE");
    }
  }, [watchedRecurringInterval, watchedRecurringBilledPeriodMode, setValue]);

  useEffect(() => {
    if (!watchedRecurring) {
      setValue("recurringBilledPeriodMode", "NONE");
    }
  }, [watchedRecurring, setValue]);

  const selectedCustomer = customers.find((c) => c.id === watchedCustomerId);
  const billingLines = selectedCustomer
    ? formatBillingAddress(selectedCustomer.billingAddress)
    : [];

  // Totals (vatRate is percentage; convert to decimal for math)
  const vatDecimal = parseFloat(watchedVatRate || "0") / 100;
  const sym = CURRENCY_SYMBOL[watchedCurrency] ?? watchedCurrency;

  function fmtAmt(value: number): string {
    return formatCurrency(
      value,
      watchedCurrency as Currency,
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

  let subtotal: number, vatAmount: number, total: number, afterTaxDiscount = 0;
  if (watchedVatIncluded && vatDecimal > 0) {
    const taxable = discountBefore ? lineSum - discountAmount : lineSum;
    total = taxable;
    vatAmount = total * vatDecimal / (1 + vatDecimal);
    subtotal = lineSum;
    if (!discountBefore && watchedDiscountType !== "NONE") {
      afterTaxDiscount = watchedDiscountType === "PERCENTAGE" ? total * (discountVal / 100) : Math.min(discountVal, total);
      total = total - afterTaxDiscount;
    }
  } else {
    const taxable = discountBefore ? lineSum - discountAmount : lineSum;
    subtotal = lineSum;
    vatAmount = taxable * vatDecimal;
    total = taxable + vatAmount;
    if (!discountBefore && watchedDiscountType !== "NONE") {
      afterTaxDiscount = watchedDiscountType === "PERCENTAGE" ? total * (discountVal / 100) : Math.min(discountVal, total);
      total = total - afterTaxDiscount;
    }
  }
  const totalDiscountAmount = discountBefore ? discountAmount : afterTaxDiscount;

  const recurringAutoBilledPreviews = useMemo(() => {
    if (
      !watchedRecurring ||
      watchedRecurringInterval !== "MONTHLY" ||
      watchedRecurringBilledPeriodMode !== "AUTO_BY_ISSUE_DATE"
    ) {
      return {
        manualFull: false,
        thisInvoice: null as ReturnType<typeof computeAutoBilledPeriodFromIssueDate> | null,
        firstRun: null as ReturnType<typeof computeAutoBilledPeriodFromIssueDate> | null,
      };
    }
    const manualFull = Boolean(watchedPeriodFrom?.trim() && watchedPeriodTo?.trim());
    let thisInvoice: ReturnType<typeof computeAutoBilledPeriodFromIssueDate> | null = null;
    if (!manualFull && watchedIssuedAt?.trim()) {
      const d = new Date(watchedIssuedAt);
      if (!Number.isNaN(d.getTime())) thisInvoice = computeAutoBilledPeriodFromIssueDate(d);
    }
    let firstRun: ReturnType<typeof computeAutoBilledPeriodFromIssueDate> | null = null;
    if (watchedRecurringStartDate?.trim()) {
      const d = new Date(watchedRecurringStartDate);
      if (!Number.isNaN(d.getTime())) firstRun = computeAutoBilledPeriodFromIssueDate(d);
    }
    return { manualFull, thisInvoice, firstRun };
  }, [
    watchedRecurring,
    watchedRecurringInterval,
    watchedRecurringBilledPeriodMode,
    watchedPeriodFrom,
    watchedPeriodTo,
    watchedIssuedAt,
    watchedRecurringStartDate,
  ]);

  const invoiceNumberPreviewSample = useMemo(() => {
    if (!orgInvoiceNumbering || invoiceId) return null;
    return previewInvoiceDocNumber(
      {
        invoiceNumberPrefix: orgInvoiceNumbering.invoiceNumberPrefix,
        invoiceNumberFormat: orgInvoiceNumbering.invoiceNumberFormat,
        invoiceNumberPadding: orgInvoiceNumbering.invoiceNumberPadding,
        invoiceNumberRandomLength: orgInvoiceNumbering.invoiceNumberRandomLength,
      },
      {
        prefixOverride: watchedInvoiceNumberPrefixOverride?.trim() || undefined,
      }
    );
  }, [
    orgInvoiceNumbering,
    invoiceId,
    watchedInvoiceNumberPrefixOverride,
  ]);

  // ─── Live preview ─────────────────────────────────────────────────────────

  const previewKey = JSON.stringify({
    customerId: watchedCustomerId,
    currency: watchedCurrency,
    vatRate: watchedVatRate,
    vatIncluded: watchedVatIncluded,
    dueDate: watchedDueDate,
    issuedAt: watchedIssuedAt,
    periodFrom: watchedPeriodFrom,
    periodTo: watchedPeriodTo,
    notes: watchedNotes,
    termsAndConditions: watchedTermsAndConditions,
    template: watchedTemplate,
    lines: watchedLines,
    discountType: watchedDiscountType,
    discountValue: watchedDiscountValue,
    discountBeforeTax: watchedDiscountBeforeTax,
  });

  const fetchPreview = useCallback(
    async (payload: object, signal: AbortSignal) => {
      const res = await fetch("/api/pdf/invoice/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ orgSlug, ...payload }),
        signal,
      });
      if (!res.ok) throw new Error("Preview failed");
      return res.blob();
    },
    [orgSlug]
  );

  useEffect(() => {
    let aborted = false;
    const controller = new AbortController();

    const timer = setTimeout(async () => {
      setPreviewLoading(true);
      setPreviewError(false);
      try {
        const blob = await fetchPreview(
          {
            customerId: watchedCustomerId || undefined,
            currency: watchedCurrency,
            vatRate: (parseFloat(watchedVatRate || "0") / 100).toString(),
            dueDate: watchedDueDate,
            issuedAt: watchedIssuedAt,
            periodFrom: watchedPeriodFrom,
            periodTo: watchedPeriodTo,
            notes: watchedNotes,
            termsAndConditions: watchedTermsAndConditions || undefined,
            template: watchedTemplate || undefined,
            vatIncluded: watchedVatIncluded,
            discountType: watchedDiscountType,
            discountValue: watchedDiscountValue,
            discountBeforeTax: watchedDiscountBeforeTax,
            lines: watchedLines
              .filter((l) => l.name || l.description || parseFloat(l.quantity || "0") > 0)
              .map((l) => ({
                name: l.name || "",
                description: l.description || undefined,
                quantity: l.quantity || "0",
                qtyType: l.qtyType || "QTY",
                unitPrice: l.unitPrice || "0",
              })),
          },
          controller.signal
        );
        const url = URL.createObjectURL(blob);
        if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = url;
        if (!aborted) setPreviewBlobUrl(url);
      } catch (e) {
        if (e instanceof Error && e.name === "AbortError") return;
        if (!aborted) setPreviewError(true);
      } finally {
        if (!controller.signal.aborted && !aborted) setPreviewLoading(false);
      }
    }, 650);

    return () => {
      aborted = true;
      clearTimeout(timer);
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewKey, orgSlug, fetchPreview]);

  useEffect(() => {
    return () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
    };
  }, []);

  // ─── Submit ───────────────────────────────────────────────────────────────

  const handleFormSubmit = async (data: InvoiceFormValues) => {
    setIsSubmitting(true);
    setError(null);
    try {
      await onSubmit({
        ...data,
        // convert percentage back to decimal before sending to server
        vatRate: (parseFloat(data.vatRate || "0") / 100).toString(),
        paymentMethod: data.paymentMethod || undefined,
        template: data.template || undefined,
        invoiceNumberPrefixOverride: data.invoiceNumberPrefixOverride?.trim() || undefined,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "An error occurred");
    } finally {
      setIsSubmitting(false);
    }
  };

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const submitHandler = (handleSubmit as any)(handleFormSubmit);

  const savedPdfHref = invoiceId ? `/api/pdf/invoice/${invoiceId}?preview=1` : null;

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="grid grid-cols-1 xl:grid-cols-[3fr_2fr] gap-8 items-start">
      {/* ══ LEFT: form ══════════════════════════════════════════════════════════ */}
      <form onSubmit={submitHandler} className="space-y-0">
        {error && (
          <div className="mb-6 rounded-md bg-red-50 border border-red-200 p-4 text-red-800 text-sm">
            {error}
          </div>
        )}

        {/* ── Section 1: Customer & Billing ───────────────────────────── */}
        <div className="pb-8">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-5">
            Customer &amp; Billing
          </p>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Left: customer select + bill-to card */}
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="customerId">
                  Customer <span className="text-red-500">*</span>
                </Label>
                <Controller
                  control={control}
                  name="customerId"
                  render={({ field }) => (
                    <Select
                      value={field.value || ""}
                      onValueChange={(value) =>
                        field.onChange(value === NO_CUSTOMER_VALUE ? "" : value)
                      }
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Select a customer…" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={NO_CUSTOMER_VALUE}>No customer (preview only)</SelectItem>
                        {customers.map((c) => (
                          <SelectItem key={c.id} value={c.id}>{c.label}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                />
                {errors.customerId && (
                  <p className="text-xs text-red-600">{errors.customerId.message}</p>
                )}
              </div>

              {/* Bill To block */}
              <div className="rounded-lg border border-[var(--border)] bg-[var(--muted)]/40 p-4 min-h-[96px]">
                <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-2">
                  Bill To
                </p>
                {selectedCustomer ? (
                  <div className="space-y-0.5 text-sm">
                    <p className="font-semibold text-[var(--foreground)]">
                      {selectedCustomer.label}
                    </p>
                    {selectedCustomer.email && (
                      <p className="text-[var(--muted-foreground)]">
                        {selectedCustomer.email}
                      </p>
                    )}
                    {selectedCustomer.vat && (
                      <p className="text-[var(--muted-foreground)] text-xs">
                        VAT: {selectedCustomer.vat}
                      </p>
                    )}
                    {billingLines.length > 0 && (
                      <div className="pt-1.5 text-xs text-[var(--muted-foreground)] leading-5">
                        {billingLines.map((line, i) => (
                          <p key={i}>{line}</p>
                        ))}
                      </div>
                    )}
                  </div>
                ) : (
                  <p className="text-xs text-[var(--muted-foreground)] italic">
                    Select a customer above to see billing details
                  </p>
                )}
              </div>
            </div>

            {/* Right: invoice settings */}
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label>Currency</Label>
                <Controller
                  control={control}
                  name="currency"
                  render={({ field }) => (
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {CURRENCIES.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>

              <div className="space-y-1.5">
                <Label>Payment Method</Label>
                <Controller
                  control={control}
                  name="paymentMethod"
                  render={({ field }) => (
                    <Select
                      value={field.value || ""}
                      onValueChange={(value) =>
                        field.onChange(value === NO_PAYMENT_METHOD_VALUE ? "" : value)
                      }
                    >
                      <SelectTrigger><SelectValue placeholder="No preference" /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value={NO_PAYMENT_METHOD_VALUE}>No preference</SelectItem>
                        {PAYMENT_METHODS.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>

              <div className="space-y-1.5">
                <Label>Invoice Template</Label>
                <Controller
                  control={control}
                  name="template"
                  render={({ field }) => (
                    <Select value={field.value ?? ""} onValueChange={field.onChange}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {INVOICE_TEMPLATES.map((t) => (
                          <SelectItem key={t.value} value={t.value}>
                            {t.label}{t.value === orgDefaultTemplate ? " (default)" : ""}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>
            </div>
          </div>

          {!invoiceId && orgInvoiceNumbering && (
            <div className="mt-6 rounded-lg border border-[var(--border)] bg-[var(--muted)]/25 p-4 space-y-2">
              <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)]">
                Invoice number (this invoice)
              </p>
              <div className="space-y-1.5">
                <Label htmlFor="invoiceNumberPrefixOverride" className="font-normal">
                  Custom prefix pattern <span className="text-[var(--muted-foreground)]">(optional)</span>
                </Label>
                <Input
                  id="invoiceNumberPrefixOverride"
                  className="font-mono text-sm"
                  placeholder={`Default: ${orgInvoiceNumbering.invoiceNumberPrefix || "INV"}`}
                  {...register("invoiceNumberPrefixOverride")}
                />
                <p className="text-xs text-[var(--muted-foreground)]">
                  Overrides your organisation default for the generated number only. Format and sequence rules still come from Settings.{" "}
                  Use the same tokens as in Settings (<code className="rounded bg-[var(--background)] px-1">{`{YYYY}`}</code>,{" "}
                  <code className="rounded bg-[var(--background)] px-1">{`{RANDOM}`}</code>, …).
                </p>
                {invoiceNumberPreviewSample && (
                  <p className="text-xs text-[var(--muted-foreground)] pt-1">
                    Example next number:{" "}
                    <span className="font-mono text-[var(--foreground)]">{invoiceNumberPreviewSample}</span>
                  </p>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="border-t border-[var(--border)]" />

        {/* ── Section 2: Invoice Dates & Tax ──────────────────────────── */}
        <div className="py-8">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-5">
            Invoice Details
          </p>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="space-y-1.5">
              <Label htmlFor="issuedAt">Invoice Date</Label>
              <Input id="issuedAt" type="date" {...register("issuedAt")} />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="dueDate">Due Date</Label>
              <Input id="dueDate" type="date" {...register("dueDate")} />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="vatRate">
                Tax / VAT{" "}
                <span className="font-normal text-[var(--muted-foreground)]">(%)</span>
              </Label>
              <div className="relative">
                <Input
                  id="vatRate"
                  type="number"
                  step="0.1"
                  min="0"
                  max="100"
                  placeholder="0"
                  className="pr-8"
                  {...register("vatRate")}
                />
                <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-[var(--muted-foreground)] pointer-events-none select-none">
                  %
                </span>
              </div>
            </div>
          </div>

          {/* Invoiced Period */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="space-y-1.5">
              <Label htmlFor="periodFrom">
                Invoiced Period{" "}
                <span className="font-normal text-[var(--muted-foreground)]">(optional)</span>
              </Label>
              <Input
                id="periodFrom"
                type="date"
                placeholder="From"
                disabled={Boolean(
                  watchedRecurring &&
                    watchedRecurringInterval === "MONTHLY" &&
                    watchedRecurringBilledPeriodMode === "AUTO_BY_ISSUE_DATE"
                )}
                {...register("periodFrom")}
              />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="periodTo" className="invisible">To</Label>
              <Input
                id="periodTo"
                type="date"
                placeholder="To"
                disabled={Boolean(
                  watchedRecurring &&
                    watchedRecurringInterval === "MONTHLY" &&
                    watchedRecurringBilledPeriodMode === "AUTO_BY_ISSUE_DATE"
                )}
                {...register("periodTo")}
              />
            </div>

            {vatDecimal > 0 && (
              <div className="flex items-center gap-2 pt-7">
                <input
                  id="vatIncluded"
                  type="checkbox"
                  className="h-3.5 w-3.5 rounded border-[var(--border)] accent-[var(--primary)] cursor-pointer"
                  {...register("vatIncluded")}
                />
                <label htmlFor="vatIncluded" className="text-xs text-[var(--muted-foreground)] cursor-pointer select-none">
                  Prices include VAT
                </label>
              </div>
            )}
          </div>
        </div>

        <div className="border-t border-[var(--border)]" />

        {/* ── Section 3: Line Items ────────────────────────────────────── */}
        <div className="py-8">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-5">
            Line Items
          </p>

          <Table className="min-w-[640px] w-full">
            <TableHeader>
              <TableRow className="border-b border-[var(--border)] bg-[var(--secondary)] hover:bg-[var(--muted)]">
                <TableHead className="h-9 py-2 px-2 text-[10px] font-semibold uppercase tracking-wide text-[var(--muted-foreground)] align-bottom">
                  Item / Service
                </TableHead>
                <TableHead className="h-9 py-2 px-2 text-[10px] font-semibold uppercase tracking-wide text-[var(--muted-foreground)] text-right align-bottom w-[104px] sm:w-[112px]">
                  Qty
                </TableHead>
                <TableHead className="h-9 py-2 px-2 text-[10px] font-semibold uppercase tracking-wide text-[var(--muted-foreground)] text-right align-bottom w-[112px] sm:w-[120px]">
                  Rate
                </TableHead>
                <TableHead className="h-9 py-2 px-2 text-[10px] font-semibold uppercase tracking-wide text-[var(--muted-foreground)] text-right align-bottom w-[100px]">
                  Amount
                </TableHead>
                <TableHead className="h-9 w-11 p-2 align-bottom" aria-label="Actions" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {fields.map((field, index) => {
                const qty = parseFloat(watchedLines[index]?.quantity || "0");
                const rate = parseFloat(watchedLines[index]?.unitPrice || "0");
                const lineTotal = isNaN(qty * rate) ? 0 : qty * rate;
                const zebra =
                  index % 2 === 0
                    ? "bg-[var(--background)] hover:bg-[var(--muted)]/55"
                    : "bg-[var(--muted)]/65 hover:bg-[var(--muted)]/90";

                return (
                  <TableRow
                    key={field.id}
                    className={cn(
                      zebra,
                      "transition-colors data-[state=selected]:bg-inherit"
                    )}
                  >
                    <TableCell className="py-2 px-2 align-top">
                      <div className="space-y-1 min-w-0">
                        <Input
                          placeholder="Item / Service name"
                          {...register(`lines.${index}.name`)}
                        />
                        {errors.lines?.[index]?.name && (
                          <p className="text-xs text-red-600 mt-0.5">
                            {errors.lines[index]?.name?.message}
                          </p>
                        )}
                        <Input
                          placeholder="Description (optional)"
                          className="text-xs text-[var(--muted-foreground)]"
                          {...register(`lines.${index}.description`)}
                        />
                      </div>
                    </TableCell>

                    <TableCell className="py-2 px-2 align-top">
                      <div className="flex flex-col gap-1">
                        <Input
                          type="number"
                          step="0.01"
                          min="0"
                          placeholder="1"
                          className="text-right tabular-nums"
                          {...register(`lines.${index}.quantity`)}
                        />
                        <Controller
                          control={control}
                          name={`lines.${index}.qtyType`}
                          render={({ field }) => (
                            <Select value={field.value} onValueChange={field.onChange}>
                              <SelectTrigger className="h-7 text-[10px] px-1.5 py-0 text-[var(--muted-foreground)]">
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
                    </TableCell>

                    <TableCell className="py-2 px-2 align-top">
                      <div className="relative">
                        <span className="absolute left-2.5 top-[9px] text-xs text-[var(--muted-foreground)] pointer-events-none select-none">
                          {sym}
                        </span>
                        <Input
                          type="number"
                          step="0.01"
                          min="0"
                          placeholder="0.00"
                          className="pl-6 text-right tabular-nums"
                          {...register(`lines.${index}.unitPrice`)}
                        />
                      </div>
                    </TableCell>

                    <TableCell className="py-2 px-2 align-top">
                      <div className="min-h-10 flex items-center justify-end pr-0.5">
                        <span className="text-sm font-medium tabular-nums text-[var(--foreground)]">
                          {fmtAmt(lineTotal)}
                        </span>
                      </div>
                    </TableCell>

                    <TableCell className="py-2 px-1 align-top w-11">
                      <button
                        type="button"
                        onClick={() => remove(index)}
                        disabled={fields.length === 1}
                        className="mt-1 h-8 w-8 flex items-center justify-center rounded-md text-[var(--muted-foreground)] hover:text-[var(--destructive)] hover:bg-[var(--destructive)]/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                        aria-label="Remove line"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>

          {errors.lines?.root && (
            <p className="text-sm text-red-600 mt-2">{errors.lines.root.message}</p>
          )}

          {/* Add item + Bill task + Discount + totals */}
          <div className="mt-5 flex items-start justify-between gap-6 flex-wrap">
            <div className="flex gap-2 flex-wrap">
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
                    taskId: undefined,
                  })
                }
              >
                <Plus className="h-3.5 w-3.5" />
                Add Item
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={!watchedCustomerId}
                onClick={async () => {
                  if (!watchedCustomerId) return;
                  setBillTaskOpen(true);
                  setBillTaskLoading(true);
                  setSelectedTaskIds(new Set());
                  const res = await getBillableTasks(
                    orgSlug,
                    watchedCustomerId,
                    billableTasksProjectId || undefined
                  );
                  const availableTasks = (res.tasks ?? []).filter(
                    (task) => !selectedTaskIdsInLines.has(task.id)
                  );
                  setBillableTasks(availableTasks);
                  setBillTaskLoading(false);
                }}
              >
                <ClipboardList className="h-3.5 w-3.5" />
                Bill Task
              </Button>
            </div>

            <div className="min-w-[260px] space-y-1.5 text-sm">
              {/* Discount controls */}
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
                    min="0"
                    placeholder={watchedDiscountType === "PERCENTAGE" ? "%" : "0.00"}
                    className="h-7 text-xs w-20 text-right tabular-nums"
                    {...register("discountValue")}
                  />
                )}
                {watchedDiscountType !== "NONE" && (
                  <Controller
                    control={control}
                    name="discountBeforeTax"
                    render={({ field }) => (
                      <Select value={field.value ? "before" : "after"} onValueChange={(v) => field.onChange(v === "before")}>
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

              {/* Totals */}
              <div className="flex justify-between gap-10">
                <span className="text-[var(--muted-foreground)]">
                  Sub Total{watchedVatIncluded && vatDecimal > 0 ? " (excl. VAT)" : ""}
                </span>
                <span className="tabular-nums font-medium">{fmtAmt(lineSum)}</span>
              </div>
              {watchedDiscountType !== "NONE" && discountBefore && totalDiscountAmount > 0 && (
                <div className="flex justify-between gap-10 text-green-600">
                  <span>Discount</span>
                  <span className="tabular-nums font-medium">-{fmtAmt(totalDiscountAmount)}</span>
                </div>
              )}
              <div className="flex justify-between gap-10">
                <span className="text-[var(--muted-foreground)]">
                  VAT{watchedVatIncluded ? " (incl.)" : ""} ({(vatDecimal * 100).toFixed(1)}%)
                </span>
                <span className="tabular-nums font-medium">{fmtAmt(vatAmount)}</span>
              </div>
              {watchedDiscountType !== "NONE" && !discountBefore && totalDiscountAmount > 0 && (
                <div className="flex justify-between gap-10 text-green-600">
                  <span>Discount (after tax)</span>
                  <span className="tabular-nums font-medium">-{fmtAmt(totalDiscountAmount)}</span>
                </div>
              )}
              <div className="border-t border-[var(--border)] pt-2 flex justify-between gap-10 font-semibold text-base">
                <span>Total</span>
                <span className="tabular-nums">{fmtAmt(total)}</span>
              </div>
            </div>
          </div>
        </div>

        <div className="border-t border-[var(--border)]" />

        {/* ── Section 4: Notes & T&C ──────────────────────────────────── */}
        <div className="py-8">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)] mb-5">
            Notes &amp; Terms
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <Label>
                Client Notes
                <span className="ml-1.5 text-[10px] text-[var(--muted-foreground)] font-normal">(visible to customer)</span>
              </Label>
              <Textarea
                placeholder="Payment instructions, deadlines, or any notes visible to the customer…"
                rows={4}
                {...register("notes")}
              />
            </div>
            <div className="space-y-1.5">
              <Label>
                Terms &amp; Conditions
                <span className="ml-1.5 text-[10px] text-[var(--muted-foreground)] font-normal">(visible to customer)</span>
              </Label>
              <Textarea
                placeholder="Standard terms, payment conditions, late fees…"
                rows={4}
                {...register("termsAndConditions")}
              />
            </div>
          </div>
          <div className="mt-4 space-y-1.5">
            <Label>
              Admin Note
              <span className="ml-1.5 text-[10px] text-[var(--muted-foreground)] font-normal">(internal, not shown to customer)</span>
            </Label>
            <Textarea
              placeholder="Internal notes, reminders, or context visible only to staff…"
              rows={3}
              {...register("adminNote")}
            />
          </div>
        </div>

        <div className="border-t border-[var(--border)]" />

        {/* ── Section 5: Recurring ─────────────────────────────────────── */}
        <div className="py-8">
          <div className="flex items-center justify-between mb-4">
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-widest text-[var(--muted-foreground)]">
                Recurring Invoice
              </p>
              <p className="text-xs text-[var(--muted-foreground)] mt-0.5">
                Automatically generate this invoice on a schedule
              </p>
            </div>
            <Controller
              control={control}
              name="recurring"
              render={({ field }) => (
                <Switch checked={!!field.value} onCheckedChange={field.onChange} />
              )}
            />
          </div>

          {watchedRecurring && (
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div className="space-y-1.5">
                <Label>Interval</Label>
                <Controller
                  control={control}
                  name="recurringInterval"
                  render={({ field }) => (
                    <Select value={field.value ?? "MONTHLY"} onValueChange={field.onChange}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="WEEKLY">Weekly</SelectItem>
                        <SelectItem value="BIWEEKLY">Bi-weekly</SelectItem>
                        <SelectItem value="MONTHLY">Monthly</SelectItem>
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>
              <div className="space-y-1.5">
                <Label>First run date</Label>
                <Input type="date" {...register("recurringStartDate")} />
              </div>
              <div className="space-y-1.5">
                <Label>End date <span className="text-[10px] text-[var(--muted-foreground)]">(optional)</span></Label>
                <Input type="date" {...register("recurringEndDate")} />
              </div>
            </div>
          )}

          {watchedRecurring && watchedRecurringInterval === "MONTHLY" && (
            <div className="mt-4 rounded-md border border-[var(--border)] bg-[var(--muted)]/20 px-4 py-3">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <p className="text-sm font-medium">Auto billed period</p>
                  <p className="text-xs text-[var(--muted-foreground)] mt-1 leading-relaxed">
                    From each invoice&apos;s issue date (UTC): if issued on the 1st, the period is the
                    full previous calendar month; otherwise it is one calendar month ending the day before
                    issue (e.g. issued 16 May → 15 Apr–15 May).
                  </p>
                </div>
                <Controller
                  control={control}
                  name="recurringBilledPeriodMode"
                  render={({ field }) => (
                    <Switch
                      className="shrink-0"
                      checked={field.value === "AUTO_BY_ISSUE_DATE"}
                      onCheckedChange={(on) => {
                        field.onChange(on ? "AUTO_BY_ISSUE_DATE" : "NONE");
                        if (on) {
                          setValue("periodFrom", "");
                          setValue("periodTo", "");
                        }
                      }}
                    />
                  )}
                />
              </div>
              {watchedRecurringBilledPeriodMode === "AUTO_BY_ISSUE_DATE" && (
                <div className="mt-3 space-y-2 border-t border-[var(--border)] pt-3 text-xs text-[var(--muted-foreground)] leading-relaxed">
                  {recurringAutoBilledPreviews.manualFull ? (
                    <p>
                      <span className="font-medium text-[var(--foreground)]">This invoice</span> uses the manual
                      invoiced period above (
                      <span className="font-medium text-[var(--foreground)] tabular-nums">
                        {formatDate(watchedPeriodFrom)} – {formatDate(watchedPeriodTo)}
                      </span>
                      ).
                    </p>
                  ) : recurringAutoBilledPreviews.thisInvoice ? (
                    <p>
                      <span className="font-medium text-[var(--foreground)]">This invoice</span> (issue{" "}
                      <span className="font-medium text-[var(--foreground)] tabular-nums">
                        {formatDate(watchedIssuedAt)}
                      </span>
                      ) - <span className="font-medium text-[var(--foreground)]">Billed period</span>:{" "}
                      <span className="font-medium text-[var(--foreground)] tabular-nums">
                        {formatDate(recurringAutoBilledPreviews.thisInvoice.periodFrom)} –{" "}
                        {formatDate(recurringAutoBilledPreviews.thisInvoice.periodTo)}
                      </span>
                    </p>
                  ) : (
                    <p>Set the invoice date to preview this invoice&apos;s billed period.</p>
                  )}
                  {recurringAutoBilledPreviews.firstRun && watchedRecurringStartDate?.trim() ? (
                    <p>
                      <span className="font-medium text-[var(--foreground)]">First scheduled run</span> (
                      <span className="font-medium text-[var(--foreground)] tabular-nums">
                        {formatDate(watchedRecurringStartDate)}
                      </span>
                      ) - <span className="font-medium text-[var(--foreground)]">Billed period</span>:{" "}
                      <span className="font-medium text-[var(--foreground)] tabular-nums">
                        {formatDate(recurringAutoBilledPreviews.firstRun.periodFrom)} –{" "}
                        {formatDate(recurringAutoBilledPreviews.firstRun.periodTo)}
                      </span>
                    </p>
                  ) : (
                    <p className="text-[var(--muted-foreground)]">
                      Set the first run date to preview the billed period for the first scheduled invoice.
                    </p>
                  )}
                  <p className="text-[10px] text-[var(--muted-foreground)]">
                    Later runs use each run&apos;s issue date (UTC), usually the day the invoice is generated.
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        <div className="border-t border-[var(--border)]" />

        {/* ── Actions ──────────────────────────────────────────────────── */}
        <div className="pt-6 flex justify-end gap-3">
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Saving…" : submitLabel}
          </Button>
        </div>
      </form>

      {/* ── Bill Task Dialog ─────────────────────────────────────────────── */}
      <Dialog open={billTaskOpen} onOpenChange={setBillTaskOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>Bill Task Hours</DialogTitle>
          </DialogHeader>
          {billTaskLoading ? (
            <div className="py-8 text-center text-sm text-[var(--muted-foreground)]">Loading tasks…</div>
          ) : billableTasks.length === 0 ? (
            <div className="py-8 text-center text-sm text-[var(--muted-foreground)]">
              No tasks with unbilled hours found for this customer.
            </div>
          ) : (
            <div className="space-y-2 max-h-[60vh] overflow-y-auto pr-1">
              {billableTasks.map((task) => {
                const selected = selectedTaskIds.has(task.id);
                return (
                  <div
                    key={task.id}
                    onClick={() => {
                      setSelectedTaskIds((prev) => {
                        const next = new Set(prev);
                        if (next.has(task.id)) next.delete(task.id);
                        else next.add(task.id);
                        return next;
                      });
                    }}
                    className={`flex items-center gap-3 rounded-lg border p-3 cursor-pointer transition-colors ${
                      selected
                        ? "border-[var(--primary)] bg-[var(--primary)]/5"
                        : "border-[var(--border)] hover:bg-[var(--accent)]"
                    }`}
                  >
                    <div className={`w-4 h-4 rounded border flex items-center justify-center shrink-0 ${
                      selected ? "bg-[var(--primary)] border-[var(--primary)]" : "border-[var(--border)]"
                    }`}>
                      {selected && <span className="text-white text-[10px] font-bold">✓</span>}
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{task.title}</p>
                      <p className="text-xs text-[var(--muted-foreground)]">{task.projectName}</p>
                    </div>
                    <div className="text-right shrink-0">
                      <p className="text-sm font-semibold tabular-nums">{task.unbilledHours.toFixed(2)} hrs</p>
                      {parseFloat(task.hourlyRate) > 0 && (
                        <p className="text-xs text-[var(--muted-foreground)]">@ {task.hourlyRate}/hr</p>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setBillTaskOpen(false)}>Cancel</Button>
            <Button
              disabled={selectedTaskIds.size === 0}
              onClick={() => {
                const existingTaskIds = new Set(
                  (watch("lines") ?? [])
                    .map((line) => line.taskId)
                    .filter((taskId): taskId is string => Boolean(taskId))
                );
                const tasksToAdd = billableTasks
                  .filter((t) => selectedTaskIds.has(t.id))
                  .filter((t) => !existingTaskIds.has(t.id));

                tasksToAdd.forEach((task, index) => {
                    append({
                      name: task.title,
                      description: "",
                      quantity: task.unbilledHours.toFixed(2),
                      qtyType: "HOURS",
                      unitPrice: task.hourlyRate || "0",
                      sortOrder: fields.length + index,
                      taskId: task.id,
                    });
                  });
                setBillTaskOpen(false);
              }}
            >
              Add {selectedTaskIds.size} task{selectedTaskIds.size !== 1 ? "s" : ""} to invoice
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ══ RIGHT: live preview pane (desktop only) ═════════════════════════════ */}
      <div className="hidden xl:flex flex-col gap-3 xl:sticky xl:top-6 h-[calc(100vh-7rem)]">
        {/* Toolbar */}
        <div className="flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-[var(--muted-foreground)]">
              {invoiceNumber ? `Preview · ${invoiceNumber}` : "Invoice Preview"}
            </span>
            {previewLoading && (
              <RefreshCw className="h-3.5 w-3.5 animate-spin text-[var(--muted-foreground)]" />
            )}
          </div>
          <div className="flex items-center gap-1.5">
            {previewBlobUrl && !previewError && (
              <a
                href={previewBlobUrl}
                download={
                  invoiceNumber
                    ? `${invoiceNumber}-preview.pdf`
                    : "invoice-preview.pdf"
                }
                className="inline-flex items-center gap-1.5 text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)] px-2 py-1 rounded border border-[var(--border)] transition-colors"
              >
                <FileDown className="h-3.5 w-3.5" />
                Download
              </a>
            )}
            {savedPdfHref && (
              <a
                href={savedPdfHref}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1.5 text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)] px-2 py-1 rounded border border-[var(--border)] transition-colors"
              >
                <ExternalLink className="h-3.5 w-3.5" />
                Open saved
              </a>
            )}
          </div>
        </div>

        {/* Preview pane */}
        <div className="relative flex-1 rounded-xl border border-[var(--border)] overflow-hidden bg-slate-100 shadow-inner">
          {!watchedCustomerId ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center text-center p-8">
              <div className="w-14 h-14 rounded-full bg-[var(--muted)] flex items-center justify-center mb-4">
                <FileDown className="h-6 w-6 text-[var(--muted-foreground)]" />
              </div>
              <p className="text-sm font-semibold text-[var(--foreground)]">No preview yet</p>
              <p className="text-xs text-[var(--muted-foreground)] mt-1.5 max-w-[180px]">
                Select a customer and add at least one line item
              </p>
            </div>
          ) : previewLoading && !previewBlobUrl ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <RefreshCw className="h-6 w-6 animate-spin text-[var(--muted-foreground)]" />
              <p className="text-sm text-[var(--muted-foreground)]">Generating preview…</p>
            </div>
          ) : previewError && !previewBlobUrl ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center text-center p-8">
              <p className="text-sm text-red-600 font-medium">Preview unavailable</p>
              <p className="text-xs text-[var(--muted-foreground)] mt-1">
                Complete the required fields to generate a preview
              </p>
            </div>
          ) : previewBlobUrl ? (
            <>
              {previewLoading && (
                <div className="absolute inset-0 z-10 flex items-center justify-center bg-white/60 backdrop-blur-[1px]">
                  <div className="flex items-center gap-2 bg-white rounded-full px-3 py-1.5 shadow-sm border border-[var(--border)]">
                    <RefreshCw className="h-3.5 w-3.5 animate-spin text-[var(--muted-foreground)]" />
                    <span className="text-xs text-[var(--muted-foreground)]">Updating…</span>
                  </div>
                </div>
              )}
              <iframe
                src={previewBlobUrl}
                className="w-full h-full border-0"
                title="Invoice Preview"
              />
            </>
          ) : null}
        </div>

        <p className="text-xs text-[var(--muted-foreground)] text-center flex-shrink-0">
          Preview updates automatically · matches the final PDF
        </p>
      </div>
    </div>
  );
}
