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
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Loader2, Plus, Trash2 } from "lucide-react";
import { createCustomer, updateCustomer, type CustomerInput } from "@/lib/actions/customers";
import { CustomerType, Currency, CustomerNotificationPurpose } from "@prisma/client";

interface Props {
  orgSlug: string;
  customerId?: string;
  defaultValues?: Partial<CustomerInput>;
}

const CURRENCIES = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const;

function initialNotifyList(
  rows: CustomerInput["notificationEmails"] | undefined,
  purpose: CustomerNotificationPurpose
): string[] {
  return (rows ?? []).filter((n) => n.purpose === purpose).map((n) => n.email);
}

export function CustomerForm({ orgSlug, customerId, defaultValues }: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState<CustomerInput>({
    type: defaultValues?.type ?? "B2B",
    companyName: defaultValues?.companyName ?? "",
    firstName: defaultValues?.firstName ?? "",
    lastName: defaultValues?.lastName ?? "",
    email: defaultValues?.email ?? "",
    phone: defaultValues?.phone ?? "",
    vat: defaultValues?.vat ?? "",
    preferredCurrency: defaultValues?.preferredCurrency ?? "EUR",
    notes: defaultValues?.notes ?? "",
    billingAddress: defaultValues?.billingAddress ?? {},
    shippingAddress: defaultValues?.shippingAddress ?? {},
    hideEmailOnInvoice: defaultValues?.hideEmailOnInvoice ?? false,
    hidePhoneOnInvoice: defaultValues?.hidePhoneOnInvoice ?? false,
  });

  const [invoiceNotify, setInvoiceNotify] = useState<string[]>(() =>
    initialNotifyList(defaultValues?.notificationEmails, "INVOICES")
  );
  const [digestNotify, setDigestNotify] = useState<string[]>(() =>
    initialNotifyList(defaultValues?.notificationEmails, "DIGEST")
  );
  const [contractNotify, setContractNotify] = useState<string[]>(() =>
    initialNotifyList(defaultValues?.notificationEmails, "CONTRACTS")
  );

  function handleChange(
    field: keyof Omit<CustomerInput, "notificationEmails">,
    value: string | CustomerType | Currency | boolean
  ) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  function handleAddressChange(
    type: "billingAddress" | "shippingAddress",
    field: string,
    value: string
  ) {
    setForm((f) => ({
      ...f,
      [type]: { ...(f[type] as Record<string, string> || {}), [field]: value },
    }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const notificationEmails: CustomerInput["notificationEmails"] = [
        ...invoiceNotify
          .map((e) => e.trim())
          .filter(Boolean)
          .map((email) => ({ purpose: "INVOICES" as const, email })),
        ...digestNotify
          .map((e) => e.trim())
          .filter(Boolean)
          .map((email) => ({ purpose: "DIGEST" as const, email })),
        ...contractNotify
          .map((e) => e.trim())
          .filter(Boolean)
          .map((email) => ({ purpose: "CONTRACTS" as const, email })),
      ];

      const payload: CustomerInput = { ...form, notificationEmails };

      const result = customerId
        ? await updateCustomer(orgSlug, customerId, payload)
        : await createCustomer(orgSlug, payload);

      if ("error" in result && result.error) {
        setError(result.error);
      } else {
        const id = customerId ?? ("customerId" in result ? result.customerId : undefined);
        router.push(`/${orgSlug}/customers${id ? `/${id}` : ""}`);
      }
    } catch {
      setError("Something went wrong.");
    } finally {
      setLoading(false);
    }
  }

  const addr = (form.billingAddress || {}) as Record<string, string>;
  const ship = (form.shippingAddress || {}) as Record<string, string>;

  return (
    <form onSubmit={handleSubmit} className="space-y-6 max-w-2xl">
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Basic Info</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Customer type</Label>
              <Select
                value={form.type}
                onValueChange={(v) => handleChange("type", v as CustomerType)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="B2B">B2B (Business)</SelectItem>
                  <SelectItem value="B2C">B2C (Individual)</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Currency</Label>
              <Select
                value={form.preferredCurrency}
                onValueChange={(v) => handleChange("preferredCurrency", v as Currency)}
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

          {form.type === "B2B" ? (
            <div className="space-y-2">
              <Label htmlFor="companyName">Company name *</Label>
              <Input
                id="companyName"
                value={form.companyName ?? ""}
                onChange={(e) => handleChange("companyName", e.target.value)}
                required
              />
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="firstName">First name *</Label>
                <Input
                  id="firstName"
                  value={form.firstName ?? ""}
                  onChange={(e) => handleChange("firstName", e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="lastName">Last name *</Label>
                <Input
                  id="lastName"
                  value={form.lastName ?? ""}
                  onChange={(e) => handleChange("lastName", e.target.value)}
                  required
                />
              </div>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                value={form.email ?? ""}
                onChange={(e) => handleChange("email", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="phone">Phone</Label>
              <Input
                id="phone"
                value={form.phone ?? ""}
                onChange={(e) => handleChange("phone", e.target.value)}
              />
            </div>
          </div>

          <div className="space-y-3 rounded-md border border-[var(--border)] p-4">
            <p className="text-sm font-medium text-[var(--foreground)]">Invoice display</p>
            <p className="text-xs text-[var(--muted-foreground)]">
              These fields stay in the CRM; they only control what appears on invoice PDFs, emails, and the public invoice page.
            </p>
            <div className="flex items-start gap-3">
              <Checkbox
                id="hideEmailOnInvoice"
                checked={form.hideEmailOnInvoice}
                onCheckedChange={(v) => handleChange("hideEmailOnInvoice", v === true)}
              />
              <div className="grid gap-1 leading-none">
                <Label htmlFor="hideEmailOnInvoice" className="font-normal cursor-pointer">
                  Don&apos;t show email on invoice
                </Label>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <Checkbox
                id="hidePhoneOnInvoice"
                checked={form.hidePhoneOnInvoice}
                onCheckedChange={(v) => handleChange("hidePhoneOnInvoice", v === true)}
              />
              <div className="grid gap-1 leading-none">
                <Label htmlFor="hidePhoneOnInvoice" className="font-normal cursor-pointer">
                  Don&apos;t show phone on invoice
                </Label>
              </div>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="vat">VAT number</Label>
            <Input
              id="vat"
              value={form.vat ?? ""}
              onChange={(e) => handleChange("vat", e.target.value)}
              placeholder="e.g. DE123456789"
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Notification routing</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <p className="text-xs text-[var(--muted-foreground)]">
            Optional dedicated mailboxes for automated messages. When empty, the app uses the customer email and
            portal contacts as today. Invoice routing applies to invoice emails, payment receipts, and overdue
            reminders. Status digest follows each project&apos;s schedule. Contracts covers signature request
            emails.
          </p>

          <div className="space-y-2">
            <Label className="text-sm font-medium">Invoices &amp; receipts</Label>
            <p className="text-xs text-[var(--muted-foreground)]">
              Invoice sends, paid receipts, and overdue reminders.
            </p>
            <div className="space-y-2">
              {invoiceNotify.length === 0 ? (
                <p className="text-xs text-[var(--muted-foreground)]">
                  None — uses billing email and portal contacts for invoice mail.
                </p>
              ) : (
                invoiceNotify.map((email, i) => (
                  <div key={i} className="flex gap-2 items-center">
                    <Input
                      type="email"
                      value={email}
                      onChange={(e) => {
                        const next = [...invoiceNotify];
                        next[i] = e.target.value;
                        setInvoiceNotify(next);
                      }}
                      placeholder="invoices@company.com"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      className="shrink-0"
                      onClick={() => setInvoiceNotify(invoiceNotify.filter((_, j) => j !== i))}
                      aria-label="Remove"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                ))
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-1"
                onClick={() => setInvoiceNotify([...invoiceNotify, ""])}
              >
                <Plus className="h-4 w-4" />
                Add email
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label className="text-sm font-medium">Status digest</Label>
            <p className="text-xs text-[var(--muted-foreground)]">
              Project weekly / biweekly / monthly status emails (logged hours summary).
            </p>
            <div className="space-y-2">
              {digestNotify.length === 0 ? (
                <p className="text-xs text-[var(--muted-foreground)]">
                  None — uses the primary portal contact&apos;s email.
                </p>
              ) : (
                digestNotify.map((email, i) => (
                  <div key={i} className="flex gap-2 items-center">
                    <Input
                      type="email"
                      value={email}
                      onChange={(e) => {
                        const next = [...digestNotify];
                        next[i] = e.target.value;
                        setDigestNotify(next);
                      }}
                      placeholder="pm@company.com"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      className="shrink-0"
                      onClick={() => setDigestNotify(digestNotify.filter((_, j) => j !== i))}
                      aria-label="Remove"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                ))
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-1"
                onClick={() => setDigestNotify([...digestNotify, ""])}
              >
                <Plus className="h-4 w-4" />
                Add email
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label className="text-sm font-medium">Contracts</Label>
            <p className="text-xs text-[var(--muted-foreground)]">Request signature emails.</p>
            <div className="space-y-2">
              {contractNotify.length === 0 ? (
                <p className="text-xs text-[var(--muted-foreground)]">
                  None — uses primary portal contact or billing email.
                </p>
              ) : (
                contractNotify.map((email, i) => (
                  <div key={i} className="flex gap-2 items-center">
                    <Input
                      type="email"
                      value={email}
                      onChange={(e) => {
                        const next = [...contractNotify];
                        next[i] = e.target.value;
                        setContractNotify(next);
                      }}
                      placeholder="contracts@company.com"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      className="shrink-0"
                      onClick={() => setContractNotify(contractNotify.filter((_, j) => j !== i))}
                      aria-label="Remove"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                ))
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-1"
                onClick={() => setContractNotify([...contractNotify, ""])}
              >
                <Plus className="h-4 w-4" />
                Add email
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Billing Address</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Address line 1</Label>
            <Input
              value={addr.line1 ?? ""}
              onChange={(e) => handleAddressChange("billingAddress", "line1", e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label>Address line 2</Label>
            <Input
              value={addr.line2 ?? ""}
              onChange={(e) => handleAddressChange("billingAddress", "line2", e.target.value)}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>City</Label>
              <Input
                value={addr.city ?? ""}
                onChange={(e) => handleAddressChange("billingAddress", "city", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Postal code</Label>
              <Input
                value={addr.postalCode ?? ""}
                onChange={(e) => handleAddressChange("billingAddress", "postalCode", e.target.value)}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>State / County</Label>
              <Input
                value={addr.state ?? ""}
                onChange={(e) => handleAddressChange("billingAddress", "state", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Country</Label>
              <Input
                value={addr.country ?? ""}
                onChange={(e) => handleAddressChange("billingAddress", "country", e.target.value)}
                placeholder="e.g. US"
              />
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Shipping Address</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Address line 1</Label>
            <Input
              value={ship.line1 ?? ""}
              onChange={(e) => handleAddressChange("shippingAddress", "line1", e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label>Address line 2</Label>
            <Input
              value={ship.line2 ?? ""}
              onChange={(e) => handleAddressChange("shippingAddress", "line2", e.target.value)}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>City</Label>
              <Input
                value={ship.city ?? ""}
                onChange={(e) => handleAddressChange("shippingAddress", "city", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Postal code</Label>
              <Input
                value={ship.postalCode ?? ""}
                onChange={(e) => handleAddressChange("shippingAddress", "postalCode", e.target.value)}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>State / County</Label>
              <Input
                value={ship.state ?? ""}
                onChange={(e) => handleAddressChange("shippingAddress", "state", e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Country</Label>
              <Input
                value={ship.country ?? ""}
                onChange={(e) => handleAddressChange("shippingAddress", "country", e.target.value)}
                placeholder="e.g. US"
              />
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Notes</CardTitle>
        </CardHeader>
        <CardContent>
          <Textarea
            value={form.notes ?? ""}
            onChange={(e) => handleChange("notes", e.target.value)}
            placeholder="Internal notes about this customer…"
            rows={4}
          />
        </CardContent>
      </Card>

      <div className="flex gap-3">
        <Button type="submit" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
          {customerId ? "Save changes" : "Create customer"}
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={() => router.back()}
        >
          Cancel
        </Button>
      </div>
    </form>
  );
}
