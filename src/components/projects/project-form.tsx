"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
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
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Loader2 } from "lucide-react";
import { createProject, updateProject, type ProjectInput } from "@/lib/actions/projects";
import { BillingMode, AutoInvoiceCycle, StatusEmailCycle, Currency } from "@prisma/client";

const CURRENCIES = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const;

interface Customer {
  id: string;
  type: string;
  companyName: string | null;
  firstName: string | null;
  lastName: string | null;
}

interface OrgSemiMonthlyDefaults {
  splitDay: number;
  emitDay1: number;
  emitDay2: number;
}

interface Props {
  orgSlug: string;
  customers: Customer[];
  projectId?: string;
  defaultCustomerId?: string;
  defaultValues?: Partial<ProjectInput>;
  orgSemiMonthlyDefaults?: OrgSemiMonthlyDefaults;
}

export function ProjectForm({
  orgSlug,
  customers,
  projectId,
  defaultCustomerId,
  defaultValues,
  orgSemiMonthlyDefaults,
}: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState<ProjectInput>({
    customerId: defaultValues?.customerId ?? defaultCustomerId ?? "",
    name: defaultValues?.name ?? "",
    description: defaultValues?.description ?? "",
    billingMode: defaultValues?.billingMode ?? "HOURLY",
    hourlyRate: defaultValues?.hourlyRate ?? "",
    fixedFee: defaultValues?.fixedFee ?? "",
    currency: defaultValues?.currency ?? "EUR",
    autoInvoice: defaultValues?.autoInvoice ?? false,
    autoInvoiceDay: defaultValues?.autoInvoiceDay ?? 1,
    autoInvoiceCycle: defaultValues?.autoInvoiceCycle ?? "MONTHLY",
    semiMonthlyPeriodSplitDay: defaultValues?.semiMonthlyPeriodSplitDay ?? null,
    semiMonthlyEmitDay1: defaultValues?.semiMonthlyEmitDay1 ?? null,
    semiMonthlyEmitDay2: defaultValues?.semiMonthlyEmitDay2 ?? null,
    statusEmailCycle: defaultValues?.statusEmailCycle ?? "NONE",
    startDate: defaultValues?.startDate ?? "",
    endDate: defaultValues?.endDate ?? "",
  });

  function handleChange(field: keyof ProjectInput, value: unknown) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const result = projectId
        ? await updateProject(orgSlug, projectId, form)
        : await createProject(orgSlug, form);

      if ("error" in result && result.error) {
        setError(result.error);
      } else {
        const id = projectId ?? ("projectId" in result ? result.projectId : undefined);
        router.push(`/${orgSlug}/projects${id ? `/${id}` : ""}`);
      }
    } catch {
      setError("Something went wrong.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-6 max-w-2xl">
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Project Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Customer *</Label>
            <Select
              value={form.customerId}
              onValueChange={(v) => handleChange("customerId", v)}
              required
            >
              <SelectTrigger>
                <SelectValue placeholder="Select customer…" />
              </SelectTrigger>
              <SelectContent>
                {customers.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.type === "B2B"
                      ? c.companyName
                      : `${c.firstName || ""} ${c.lastName || ""}`.trim()}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="name">Project name *</Label>
            <Input
              id="name"
              value={form.name}
              onChange={(e) => handleChange("name", e.target.value)}
              required
            />
          </div>

          <div className="space-y-2">
            <Label>Description</Label>
            <Textarea
              value={form.description ?? ""}
              onChange={(e) => handleChange("description", e.target.value)}
              rows={3}
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Start date</Label>
              <Input
                type="date"
                value={form.startDate ?? ""}
                onChange={(e) => handleChange("startDate", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>End date</Label>
              <Input
                type="date"
                value={form.endDate ?? ""}
                onChange={(e) => handleChange("endDate", e.target.value)}
              />
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Billing</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Billing mode</Label>
              <Select
                value={form.billingMode}
                onValueChange={(v) => handleChange("billingMode", v as BillingMode)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="HOURLY">Hourly</SelectItem>
                  <SelectItem value="FIXED">Fixed fee</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Currency</Label>
              <Select
                value={form.currency}
                onValueChange={(v) => handleChange("currency", v as Currency)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CURRENCIES.map((c) => (
                    <SelectItem key={c} value={c}>{c}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {form.billingMode === "HOURLY" ? (
            <div className="space-y-2">
              <Label>Hourly rate</Label>
              <Input
                type="number"
                step="0.01"
                min="0"
                value={form.hourlyRate ?? ""}
                onChange={(e) => handleChange("hourlyRate", e.target.value)}
                placeholder="100.00"
              />
            </div>
          ) : (
            <div className="space-y-2">
              <Label>Fixed fee</Label>
              <Input
                type="number"
                step="0.01"
                min="0"
                value={form.fixedFee ?? ""}
                onChange={(e) => handleChange("fixedFee", e.target.value)}
                placeholder="5000.00"
              />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Auto-invoicing</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <Label>Enable auto-invoicing</Label>
              <p className="text-xs text-[var(--muted-foreground)]">
                Automatically generate invoices from unbilled time entries
              </p>
            </div>
            <Switch
              checked={form.autoInvoice}
              onCheckedChange={(v) => handleChange("autoInvoice", v)}
            />
          </div>

          {form.autoInvoice && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label>Cycle</Label>
                  <Select
                    value={form.autoInvoiceCycle}
                    onValueChange={(v) => handleChange("autoInvoiceCycle", v as AutoInvoiceCycle)}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="MONTHLY">Monthly</SelectItem>
                      <SelectItem value="SEMI_MONTHLY">Semi-monthly (twice a month)</SelectItem>
                      <SelectItem value="BIWEEKLY">Biweekly (every 2 weeks)</SelectItem>
                      <SelectItem value="WEEKLY">Weekly</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                {form.autoInvoiceCycle !== "SEMI_MONTHLY" && (
                  <div className="space-y-2">
                    <Label>Day of month</Label>
                    <Input
                      type="number"
                      min="1"
                      max="31"
                      value={form.autoInvoiceDay ?? 1}
                      onChange={(e) => handleChange("autoInvoiceDay", parseInt(e.target.value))}
                    />
                  </div>
                )}
              </div>

              {form.autoInvoiceCycle === "SEMI_MONTHLY" && (
                <div className="rounded-lg border border-[var(--border)] p-4 space-y-4 bg-[var(--muted)]/30">
                  <p className="text-xs text-[var(--muted-foreground)]">
                    Override the org-wide semi-monthly dates for this project. Leave blank to use the org defaults
                    {orgSemiMonthlyDefaults && (
                      <> (split on day {orgSemiMonthlyDefaults.splitDay}, emit on {orgSemiMonthlyDefaults.emitDay1} &amp; {orgSemiMonthlyDefaults.emitDay2})</>
                    )}.
                  </p>
                  <div className="grid grid-cols-3 gap-4">
                    <div className="space-y-2">
                      <Label>Period split day</Label>
                      <Input
                        type="number"
                        min={1}
                        max={28}
                        value={form.semiMonthlyPeriodSplitDay ?? ""}
                        placeholder={orgSemiMonthlyDefaults ? `Org default: ${orgSemiMonthlyDefaults.splitDay}` : "15"}
                        onChange={(e) => handleChange("semiMonthlyPeriodSplitDay", e.target.value ? parseInt(e.target.value) : null)}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>Emit invoice 1 on day</Label>
                      <Input
                        type="number"
                        min={1}
                        max={31}
                        value={form.semiMonthlyEmitDay1 ?? ""}
                        placeholder={orgSemiMonthlyDefaults ? `Org default: ${orgSemiMonthlyDefaults.emitDay1}` : "16"}
                        onChange={(e) => handleChange("semiMonthlyEmitDay1", e.target.value ? parseInt(e.target.value) : null)}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>Emit invoice 2 on day</Label>
                      <Input
                        type="number"
                        min={1}
                        max={31}
                        value={form.semiMonthlyEmitDay2 ?? ""}
                        placeholder={orgSemiMonthlyDefaults ? `Org default: ${orgSemiMonthlyDefaults.emitDay2}` : "1"}
                        onChange={(e) => handleChange("semiMonthlyEmitDay2", e.target.value ? parseInt(e.target.value) : null)}
                      />
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          <div className="space-y-2">
            <Label>Status email digest</Label>
            <Select
              value={form.statusEmailCycle}
              onValueChange={(v) => handleChange("statusEmailCycle", v as StatusEmailCycle)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="NONE">Disabled</SelectItem>
                <SelectItem value="WEEKLY">Weekly</SelectItem>
                <SelectItem value="BIWEEKLY">Biweekly</SelectItem>
                <SelectItem value="MONTHLY">Monthly</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      <div className="flex gap-3">
        <Button type="submit" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {projectId ? "Save changes" : "Create project"}
        </Button>
        <Button type="button" variant="outline" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
