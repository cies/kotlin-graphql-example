"use client";

import { useRef, useState, useMemo, useEffect } from "react";
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
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Loader2,
  RefreshCw,
  CheckCircle,
  FileText,
  Building2,
  Shield,
  Upload,
  X,
  Clock,
  CreditCard,
  Mail,
  Banknote,
  PieChart,
  AlertTriangle,
  Copy,
} from "lucide-react";
import { updateOrgSettings, syncFxRates, type SettingsInput } from "@/lib/actions/settings";
import { Currency, SmtpEncryption, InvoiceTemplate, AutoInvoiceCycle, InvoicePdfFont, type TimeRounding } from "@prisma/client";
import { formatDate } from "@/lib/utils/format";
import type { SerializedFxRate, SerializedOrgSettings } from "@/lib/settings/serialize-for-client";
import { cn } from "@/lib/utils/cn";
import { previewInvoiceDocNumber, previewReceiptDocNumber } from "@/lib/invoices/number-preview";
import { computeEmailFooterLegalLine } from "@/lib/email/footer-legal-line";

const CURRENCIES = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const;
const KEYS_MASK_PLACEHOLDER = "•••••••••••••••••••••••••••";

function CredentialFieldBadge({ configured }: { configured: boolean }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium shrink-0",
        configured
          ? "border-emerald-500/35 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300"
          : "border-[var(--border)] bg-[var(--muted)]/40 text-[var(--muted-foreground)]"
      )}
      title={configured ? "A value is saved for this organisation" : "Nothing saved yet"}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full", configured ? "bg-emerald-500" : "bg-[var(--muted-foreground)]/45")} />
      {configured ? "Set · active" : "Not set"}
    </span>
  );
}

function ordinal(n: number): string {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return s[(v - 20) % 10] ?? s[v] ?? s[0] ?? "th";
}

/** Normalise hex for `<input type="color" />` (requires #rrggbb). */
function hexForColorInput(hex: string): string {
  const t = hex.trim();
  if (/^#[0-9a-fA-F]{6}$/i.test(t)) return t.toLowerCase();
  if (/^#[0-9a-fA-F]{3}$/i.test(t)) {
    const [, r, g, b] = t;
    return `#${r}${r}${g}${g}${b}${b}`.toLowerCase();
  }
  if (/^#[0-9a-fA-F]{8}$/i.test(t)) return `#${t.slice(1, 7).toLowerCase()}`;
  return "#2563eb";
}

const INVOICE_TEMPLATES: { value: InvoiceTemplate; label: string; desc: string }[] = [
  { value: "CLASSIC", label: "Classic", desc: "Clean table layout - the familiar, trusted invoice format." },
  { value: "MODERN", label: "Modern", desc: "Bold header band, dark table header, violet accent rule and totals." },
  { value: "MINIMAL", label: "Minimal", desc: "Slim header, strong accent borders, maximum whitespace." },
  { value: "CORPORATE", label: "Corporate", desc: "From / To columns, dates panel; top accent bar uses your colour below." },
];

const SETTINGS_SECTIONS = [
  { id: "company", label: "Company", icon: Building2 },
  { id: "email", label: "Email", icon: Mail },
  { id: "payments", label: "Payments", icon: CreditCard },
  { id: "invoices", label: "Invoices & PDFs", icon: FileText },
  { id: "billing", label: "Billing & currency", icon: PieChart },
  { id: "privacy", label: "Privacy & compliance", icon: Shield },
  { id: "time", label: "Time tracking", icon: Clock },
] as const;

type SettingsSectionId = (typeof SETTINGS_SECTIONS)[number]["id"];

const INVOICE_PDF_FONTS: { value: InvoicePdfFont; label: string }[] = [
  { value: "ROBOTO", label: "Roboto" },
  { value: "ARIAL", label: "Arial" },
  { value: "HELVETICA", label: "Helvetica" },
  { value: "GEIST", label: "Geist" },
  { value: "INTER", label: "Inter" },
  { value: "OPEN_SANS", label: "Open Sans" },
];

interface Props {
  orgSlug: string;
  settings: SerializedOrgSettings | null;
  /** Matches server email composer: used when Company name is empty but Organization.name exists */
  organizationDisplayName: string;
  fxRates: SerializedFxRate[];
  isPremium?: boolean;
}

export function OrgSettingsForm({
  orgSlug,
  settings,
  organizationDisplayName,
  fxRates,
  isPremium,
}: Props) {
  const cf = settings?.credentialFlags ?? {
    stripePublishableKeySet: false,
    stripeSecretKeySet: false,
    stripeWebhookSecretSet: false,
    smtpPasswordSet: false,
  };
  const stripePaymentsReady =
    cf.stripePublishableKeySet && cf.stripeSecretKeySet && cf.stripeWebhookSecretSet;

  const [stripeWebhookFullUrl, setStripeWebhookFullUrl] = useState("");
  const [stripeWebhookCopied, setStripeWebhookCopied] = useState(false);
  useEffect(() => {
    const fromEnv =
      typeof process !== "undefined" && process.env.NEXT_PUBLIC_APP_URL
        ? process.env.NEXT_PUBLIC_APP_URL.replace(/\/+$/, "")
        : "";
    const base = fromEnv || (typeof window !== "undefined" ? window.location.origin : "");
    setStripeWebhookFullUrl(base ? `${base}/api/stripe/webhook/${orgSlug}` : "");
  }, [orgSlug]);

  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [logoUploading, setLogoUploading] = useState(false);
  const [logoError, setLogoError] = useState<string | null>(null);
  const [activeSection, setActiveSection] = useState<SettingsSectionId>("company");
  const logoFileRef = useRef<HTMLInputElement>(null);

  async function handleLogoUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setLogoUploading(true);
    setLogoError(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch(`/api/uploads/logo?orgSlug=${orgSlug}`, {
        method: "POST",
        body: fd,
      });
      const json = await res.json();
      if (!res.ok) {
        setLogoError(json.error ?? "Upload failed");
      } else {
        handleChange("companyLogoUrl", json.url);
      }
    } catch {
      setLogoError("Upload failed. Please try again.");
    } finally {
      setLogoUploading(false);
      if (logoFileRef.current) logoFileRef.current.value = "";
    }
  }

  const [form, setForm] = useState({
    displayCurrency: settings?.displayCurrency ?? "EUR",
    numberFormatStyle: settings?.numberFormatStyle ?? "COMMA_DOT",
    autoSendInvoice: settings?.autoSendInvoice ?? false,
    timeRounding: settings?.timeRounding ?? "NONE",
    vatRate: settings?.vatRate ?? "0",
    defaultVatIncluded: settings?.defaultVatIncluded ?? false,
    defaultDueDays: settings?.defaultDueDays != null ? settings.defaultDueDays.toString() : "none",
    // Numbering
    invoiceNumberPrefix: settings?.invoiceNumberPrefix ?? "INV",
    invoiceNumberFormat: settings?.invoiceNumberFormat ?? "YEAR_SEQ",
    invoiceNumberPadding: settings?.invoiceNumberPadding ?? 4,
    invoiceNumberRandomLength: settings?.invoiceNumberRandomLength ?? 6,
    receiptNumberPrefix: settings?.receiptNumberPrefix ?? "REC",
    receiptNumberFormat: settings?.receiptNumberFormat ?? "YEAR_SEQ",
    receiptNumberPadding: settings?.receiptNumberPadding ?? 4,
    receiptNumberRandomLength: settings?.receiptNumberRandomLength ?? 6,
    companyName: settings?.companyName?.trim() || organizationDisplayName || "",
    companyAddress: settings?.companyAddress ?? "",
    companyCity: settings?.companyCity ?? "",
    companyState: settings?.companyState ?? "",
    companyCountry: settings?.companyCountry ?? "",
    companyPostalCode: settings?.companyPostalCode ?? "",
    companyVat: settings?.companyVat ?? "",
    companyPhone: settings?.companyPhone ?? "",
    companyLogoUrl: settings?.companyLogoUrl ?? "",
    // Invoice
    defaultInvoiceTemplate: settings?.defaultInvoiceTemplate ?? "CLASSIC",
    invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
    showInvoiceStatus: settings?.showInvoiceStatus ?? true,
    invoiceAccentColor: settings?.invoiceAccentColor ?? "",
    trackInvoiceEmailOpens: settings?.trackInvoiceEmailOpens ?? true,
    invoiceFooterText: settings?.invoiceFooterText ?? "",
    emailEnvelopeFooterLine: settings?.emailEnvelopeFooterLine ?? "",
    // Bank
    bankName: settings?.bankName ?? "",
    bankIban: settings?.bankIban ?? "",
    bankBic: settings?.bankBic ?? "",
    bankAccountHolder: settings?.bankAccountHolder ?? "",
    bankInstructions: settings?.bankInstructions ?? "",
    // Privacy
    privacyGdpr: settings?.privacyGdpr ?? false,
    privacyCcpa: settings?.privacyCcpa ?? false,
    // SMTP
    smtpHost: settings?.smtpHost ?? "",
    smtpPort: settings?.smtpPort?.toString() ?? "",
    smtpEncryption: settings?.smtpEncryption ?? "TLS",
    smtpUser: settings?.smtpUser ?? "",
    smtpFrom: settings?.smtpFrom ?? "",
    stripePublishableKey: "",
    stripeSecretKey: "",
    stripeWebhookSecret: "",
    smtpPass: "",
    // Billing schedule
    defaultAutoInvoiceCycle: (settings?.defaultAutoInvoiceCycle ?? "MONTHLY") as AutoInvoiceCycle,
    semiMonthlyPeriodSplitDay: settings?.semiMonthlyPeriodSplitDay ?? 15,
    semiMonthlyEmitDay1: settings?.semiMonthlyEmitDay1 ?? 16,
    semiMonthlyEmitDay2: settings?.semiMonthlyEmitDay2 ?? 1,
  });

  const smtpDeliveryReady = useMemo(
    () =>
      form.smtpHost.trim().length > 0 &&
      form.smtpFrom.trim().length > 0 &&
      Boolean(form.smtpPort?.toString().trim()) &&
      cf.smtpPasswordSet,
    [form.smtpHost, form.smtpPort, form.smtpFrom, cf.smtpPasswordSet]
  );

  const effectiveEmailFooterLegalLine = useMemo(
    () =>
      computeEmailFooterLegalLine({
        envelopeFooterLine: form.emailEnvelopeFooterLine.trim() || null,
        companyName: form.companyName.trim() || organizationDisplayName,
        companyAddress: form.companyAddress.trim() || null,
        companyCity: form.companyCity.trim() || null,
        companyState: form.companyState.trim() || null,
        companyPostalCode: form.companyPostalCode.trim() || null,
        companyCountry: form.companyCountry.trim() || null,
      }),
    [
      form.emailEnvelopeFooterLine,
      form.companyName,
      form.companyAddress,
      form.companyCity,
      form.companyState,
      form.companyPostalCode,
      form.companyCountry,
      organizationDisplayName,
    ]
  );

  function handleChange(
    field: string,
    value: string | boolean | number | Currency | InvoiceTemplate | InvoicePdfFont | AutoInvoiceCycle | TimeRounding
  ) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSuccess(false);

    const result = await updateOrgSettings(orgSlug, {
      ...form,
      displayCurrency: form.displayCurrency as Currency,
      numberFormatStyle: form.numberFormatStyle as "COMMA_DOT" | "DOT_COMMA",
      smtpPort: form.smtpPort ? parseInt(form.smtpPort) : undefined,
      smtpEncryption: form.smtpEncryption as SmtpEncryption,
      defaultInvoiceTemplate: form.defaultInvoiceTemplate as InvoiceTemplate,
      invoicePdfFont: form.invoicePdfFont as InvoicePdfFont,
      defaultDueDays: form.defaultDueDays !== "none" && form.defaultDueDays !== "" ? parseInt(form.defaultDueDays) : null,
      invoiceNumberPadding: typeof form.invoiceNumberPadding === "string" ? parseInt(form.invoiceNumberPadding) : form.invoiceNumberPadding,
      invoiceNumberRandomLength:
        typeof form.invoiceNumberRandomLength === "string"
          ? parseInt(form.invoiceNumberRandomLength)
          : form.invoiceNumberRandomLength,
      receiptNumberPadding: typeof form.receiptNumberPadding === "string" ? parseInt(form.receiptNumberPadding) : form.receiptNumberPadding,
      receiptNumberRandomLength:
        typeof form.receiptNumberRandomLength === "string"
          ? parseInt(form.receiptNumberRandomLength)
          : form.receiptNumberRandomLength,
      defaultAutoInvoiceCycle: form.defaultAutoInvoiceCycle,
      semiMonthlyPeriodSplitDay: form.semiMonthlyPeriodSplitDay,
      semiMonthlyEmitDay1: form.semiMonthlyEmitDay1,
      semiMonthlyEmitDay2: form.semiMonthlyEmitDay2,
      trackInvoiceEmailOpens: form.trackInvoiceEmailOpens,
      emailEnvelopeFooterLine:
        form.emailEnvelopeFooterLine.trim() === "" ? null : form.emailEnvelopeFooterLine.trim(),
    } as SettingsInput);

    setSaving(false);
    if ("error" in result && result.error) {
      setError(result.error);
    } else {
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    }
  }

  async function handleSyncFx() {
    setSyncing(true);
    const result = await syncFxRates(orgSlug);
    setSyncing(false);
    if ("error" in result && result.error) {
      setError(result.error);
    }
  }

  const invoiceTemplatesCard = (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FileText className="h-4 w-4" />
          Invoice templates
        </CardTitle>
        <CardDescription>Choose the default invoice template. Individual invoices can override this.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {INVOICE_TEMPLATES.map((t) => (
            <button
              key={t.value}
              type="button"
              onClick={() => handleChange("defaultInvoiceTemplate", t.value)}
              className={`rounded-lg border-2 p-4 text-left transition-colors ${
                form.defaultInvoiceTemplate === t.value
                  ? "border-[var(--primary)] bg-[var(--primary)]/5"
                  : "border-[var(--border)] hover:border-[var(--primary)]/50"
              }`}
            >
              <div className="font-semibold text-sm mb-1">{t.label}</div>
              <div className="text-xs text-[var(--muted-foreground)]">{t.desc}</div>
              {form.defaultInvoiceTemplate === t.value && (
                <div className="mt-2 text-xs font-medium text-[var(--primary)]">✓ Selected</div>
              )}
            </button>
          ))}
        </div>

        <div className="space-y-2">
          <Label>Invoice & receipt PDF font</Label>
          <p className="text-xs text-[var(--muted-foreground)]">
            Body typeface for generated invoice and payment receipt PDFs (Roboto, Inter, Open Sans, and Geist ship with the app;
            Arial uses system fonts when available).
          </p>
          <Select value={form.invoicePdfFont} onValueChange={(v) => handleChange("invoicePdfFont", v as InvoicePdfFont)}>
            <SelectTrigger className="max-w-md">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {INVOICE_PDF_FONTS.map((f) => (
                <SelectItem key={f.value} value={f.value}>
                  {f.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label>
            Invoice accent colour{" "}
            <span className="text-[var(--muted-foreground)] font-normal text-xs">
              (hex, e.g. #2563eb - PDF headers and accents; leave empty for each template&apos;s default)
            </span>
          </Label>
          <p className="text-xs text-[var(--muted-foreground)]">
            Applies to Classic, Modern, Minimal, and Corporate invoice PDFs.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="color"
              aria-label="Pick invoice accent colour"
              className="h-9 w-12 cursor-pointer rounded border border-[var(--border)] bg-transparent p-0.5 shrink-0"
              value={hexForColorInput(form.invoiceAccentColor)}
              onChange={(e) => handleChange("invoiceAccentColor", e.target.value)}
            />
            <Input
              value={form.invoiceAccentColor}
              onChange={(e) => handleChange("invoiceAccentColor", e.target.value)}
              placeholder="#2563eb (optional)"
              className="max-w-xs"
            />
            {form.invoiceAccentColor.trim() !== "" && (
              <div
                className="h-8 w-8 rounded border border-[var(--border)] shrink-0"
                style={{ background: form.invoiceAccentColor }}
              />
            )}
          </div>
        </div>

        <div
          className={`rounded-lg border p-4 space-y-3 ${
            isPremium ? "border-[var(--border)]" : "border-dashed border-[var(--muted-foreground)]/40 opacity-70"
          }`}
        >
          <div className="flex items-center justify-between">
            <div>
              <div className="font-semibold text-sm">Invoice footer</div>
              <div className="text-xs text-[var(--muted-foreground)]">Optional footer text shown at the bottom of PDF invoices</div>
            </div>
            {!isPremium && (
              <span className="text-xs font-medium px-2 py-1 rounded-full bg-[var(--primary)]/10 text-[var(--primary)]">
                Premium
              </span>
            )}
          </div>
          {isPremium ? (
            <div className="space-y-2">
              <Label>Invoice footer text</Label>
              <Textarea
                value={form.invoiceFooterText}
                onChange={(e) => handleChange("invoiceFooterText", e.target.value)}
                placeholder="Payment terms, bank details, legal notes..."
                rows={3}
              />
            </div>
          ) : (
            <p className="text-xs text-[var(--muted-foreground)]">
              Upgrade to Premium to add custom footer text on PDF invoices.{" "}
              <a href={`/${orgSlug}/settings/billing`} className="text-[var(--primary)] underline">
                Upgrade →
              </a>
            </p>
          )}
        </div>
        <div className="flex items-center justify-between border rounded-lg p-3">
          <div>
            <Label>Show invoice status on PDF</Label>
            <p className="text-xs text-[var(--muted-foreground)]">
              Display status badge (e.g. UNPAID/PAID) on generated invoice documents.
            </p>
          </div>
          <Switch checked={form.showInvoiceStatus} onCheckedChange={(v) => handleChange("showInvoiceStatus", v)} />
        </div>
        <div className="flex items-center justify-between border rounded-lg p-3">
          <div>
            <Label>Track invoice email opens</Label>
            <p className="text-xs text-[var(--muted-foreground)]">
              Adds a small image to invoice and overdue-reminder emails. Opens are recorded when the image loads (many clients
              block images; this is not proof the message was read).
            </p>
          </div>
          <Switch checked={form.trackInvoiceEmailOpens} onCheckedChange={(v) => handleChange("trackInvoiceEmailOpens", v)} />
        </div>
      </CardContent>
    </Card>
  );

  const invoiceNumberingCard = (
    <Card>
      <CardHeader>
        <CardTitle>Invoice &amp; receipt numbering</CardTitle>
        <CardDescription>
          Pick a number format, then define the prefix pattern. You can use plain text (e.g. INV) or tokens; see the “Tokens in the prefix” section below.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-8">
        <div className="space-y-4">
          <p className="text-sm font-medium">Invoices</p>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="space-y-2 lg:col-span-2">
              <Label>Number format</Label>
              <Select value={form.invoiceNumberFormat} onValueChange={(v) => handleChange("invoiceNumberFormat", v)}>
                <SelectTrigger className="max-w-xl">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="YEAR_SEQ">Year + sequence (PREFIX-YYYY-0001)</SelectItem>
                  <SelectItem value="YEARMONTH_SEQ">Year/month + sequence (PREFIX-YYYYMM-0001)</SelectItem>
                  <SelectItem value="SEQ_ONLY">Running sequence (PREFIX-0001)</SelectItem>
                  <SelectItem value="DATE_RANDOM">Date + random · custom pattern ({`{RANDOM}`} in prefix allowed)</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {form.invoiceNumberFormat !== "DATE_RANDOM" ? (
              <div className="space-y-2">
                <Label>Sequence padding (digits)</Label>
                <Input
                  type="number"
                  min="1"
                  max="10"
                  className="max-w-xs"
                  value={form.invoiceNumberPadding}
                  onChange={(e) => handleChange("invoiceNumberPadding", parseInt(e.target.value) || 4)}
                />
              </div>
            ) : (
              <div className="space-y-2">
                <Label>Random segment length</Label>
                <Input
                  type="number"
                  min="1"
                  max="32"
                  className="max-w-xs"
                  value={form.invoiceNumberRandomLength}
                  onChange={(e) => handleChange("invoiceNumberRandomLength", parseInt(e.target.value) || 6)}
                />
                <p className="text-xs text-[var(--muted-foreground)]">
                  Used for <code className="rounded bg-[var(--muted)]/40 px-1">{`{RANDOM}`}</code> and for the trailing
                  random block when the prefix has no token.
                </p>
              </div>
            )}
          </div>
          <div className="space-y-2">
            <Label>Prefix pattern</Label>
            <Input
              value={form.invoiceNumberPrefix}
              onChange={(e) => handleChange("invoiceNumberPrefix", e.target.value)}
              placeholder="INV"
              className="font-mono text-sm max-w-3xl"
            />
            <details className="text-xs text-[var(--muted-foreground)]">
              <summary className="cursor-pointer select-none text-[var(--foreground)]/80">Tokens in the prefix</summary>
              <p className="mt-2 pl-1 border-l-2 border-[var(--border)]">
                Use braces: <code className="rounded bg-[var(--muted)]/40 px-1">{`{YYYY}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{MM}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{DD}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{RANDOM}`}</code>. The{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{RANDOM}`}</code> token only works when the format
                above is <span className="font-medium">Date + random</span>.
              </p>
            </details>
          </div>
          <p className="text-xs text-[var(--muted-foreground)]">
            Example: <span className="font-mono text-[var(--foreground)]">{previewInvoiceDocNumber(form)}</span>
          </p>
        </div>

        <div className="border-t border-[var(--border)] pt-6 space-y-4">
          <p className="text-sm font-medium">Receipts</p>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="space-y-2 lg:col-span-2">
              <Label>Number format</Label>
              <Select value={form.receiptNumberFormat} onValueChange={(v) => handleChange("receiptNumberFormat", v)}>
                <SelectTrigger className="max-w-xl">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="YEAR_SEQ">Year + sequence (PREFIX-YYYY-0001)</SelectItem>
                  <SelectItem value="YEARMONTH_SEQ">Year/month + sequence (PREFIX-YYYYMM-0001)</SelectItem>
                  <SelectItem value="SEQ_ONLY">Running sequence (PREFIX-0001)</SelectItem>
                  <SelectItem value="DATE_RANDOM">Date + random · custom pattern ({`{RANDOM}`} in prefix allowed)</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {form.receiptNumberFormat !== "DATE_RANDOM" ? (
              <div className="space-y-2">
                <Label>Sequence padding (digits)</Label>
                <Input
                  type="number"
                  min="1"
                  max="10"
                  className="max-w-xs"
                  value={form.receiptNumberPadding}
                  onChange={(e) => handleChange("receiptNumberPadding", parseInt(e.target.value) || 4)}
                />
              </div>
            ) : (
              <div className="space-y-2">
                <Label>Random segment length</Label>
                <Input
                  type="number"
                  min="1"
                  max="32"
                  className="max-w-xs"
                  value={form.receiptNumberRandomLength}
                  onChange={(e) => handleChange("receiptNumberRandomLength", parseInt(e.target.value) || 6)}
                />
              </div>
            )}
          </div>
          <div className="space-y-2">
            <Label>Prefix pattern</Label>
            <Input
              value={form.receiptNumberPrefix}
              onChange={(e) => handleChange("receiptNumberPrefix", e.target.value)}
              placeholder="REC"
              className="font-mono text-sm max-w-3xl"
            />
            <details className="text-xs text-[var(--muted-foreground)]">
              <summary className="cursor-pointer select-none text-[var(--foreground)]/80">Tokens in the prefix</summary>
              <p className="mt-2 pl-1 border-l-2 border-[var(--border)]">
                Same as invoices: <code className="rounded bg-[var(--muted)]/40 px-1">{`{YYYY}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{MM}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{DD}`}</code>,{" "}
                <code className="rounded bg-[var(--muted)]/40 px-1">{`{RANDOM}`}</code> (with Date + random format).
              </p>
            </details>
          </div>
          <p className="text-xs text-[var(--muted-foreground)]">
            Example: <span className="font-mono text-[var(--foreground)]">{previewReceiptDocNumber(form)}</span>
          </p>
        </div>
      </CardContent>
    </Card>
  );

  return (
    <div className="flex flex-col gap-8 lg:flex-row lg:gap-12 xl:gap-14 max-w-6xl">
      <aside className="shrink-0 lg:w-[15rem]">
        <p className="text-xs font-medium uppercase tracking-wide text-[var(--muted-foreground)] mb-2 lg:px-2">
          Category
        </p>
        <nav
          aria-label="Settings categories"
          role="tablist"
          aria-orientation="vertical"
          className="-mx-1 flex gap-1 overflow-x-auto pb-2 lg:flex-col lg:overflow-visible lg:border-r lg:border-[var(--border)] lg:pb-0 lg:px-2 lg:pr-6"
        >
          {SETTINGS_SECTIONS.map(({ id, label, icon: Icon }) => {
            const selected = activeSection === id;
            return (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                id={`settings-tab-${id}`}
                aria-controls="settings-panel"
                onClick={() => setActiveSection(id)}
                className={cn(
                  "flex shrink-0 items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm transition-colors",
                  "hover:bg-[var(--muted)]/60 hover:text-[var(--foreground)]",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2",
                  selected
                    ? "bg-[var(--primary)]/10 text-[var(--foreground)] font-medium border border-[var(--primary)]/25"
                    : "text-[var(--muted-foreground)] border border-transparent"
                )}
              >
                <Icon className="h-4 w-4 shrink-0 opacity-80" aria-hidden />
                <span>{label}</span>
              </button>
            );
          })}
        </nav>
      </aside>

      <form onSubmit={handleSave} className="min-w-0 flex-1 flex flex-col gap-6 lg:max-w-3xl">
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert>
          <CheckCircle className="h-4 w-4" />
          <AlertDescription>Settings saved successfully.</AlertDescription>
        </Alert>
      )}

      <div
        className="flex flex-1 flex-col gap-4 min-h-[12rem]"
        role="tabpanel"
        id="settings-panel"
        aria-labelledby={`settings-tab-${activeSection}`}
      >
      {activeSection === "company" && (
      <section className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Building2 className="h-4 w-4" />Company</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Company name</Label>
            <Input value={form.companyName} onChange={(e) => handleChange("companyName", e.target.value)} placeholder="Acme Inc." />
          </div>
          <div className="space-y-2">
            <Label>VAT number</Label>
            <Input value={form.companyVat} onChange={(e) => handleChange("companyVat", e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>Invoice phone / contact lines</Label>
            <Textarea
              value={form.companyPhone}
              onChange={(e) => handleChange("companyPhone", e.target.value)}
              placeholder={"US, New York: 1-718-7667744\nUK, London: 44-20-80997699"}
              rows={4}
              className="font-mono text-sm"
            />
            <p className="text-xs text-muted-foreground">
              Shown on PDF invoices after your address (one line per phone or office).
            </p>
          </div>
          <div className="space-y-2">
            <Label>Street address</Label>
            <Input value={form.companyAddress} onChange={(e) => handleChange("companyAddress", e.target.value)} placeholder="123 Main St" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>City</Label>
              <Input value={form.companyCity} onChange={(e) => handleChange("companyCity", e.target.value)} placeholder="New York" />
            </div>
            <div className="space-y-2">
              <Label>Postal code</Label>
              <Input value={form.companyPostalCode} onChange={(e) => handleChange("companyPostalCode", e.target.value)} placeholder="10001" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>State / County</Label>
              <Input value={form.companyState} onChange={(e) => handleChange("companyState", e.target.value)} placeholder="NY" />
            </div>
            <div className="space-y-2">
              <Label>Country</Label>
              <Input value={form.companyCountry} onChange={(e) => handleChange("companyCountry", e.target.value)} placeholder="US" />
            </div>
          </div>
          <div className="space-y-2">
            <Label>
              Logo{" "}
              <span className="text-[var(--muted-foreground)] font-normal text-xs">
                (PNG, SVG, JPG or WebP - shown in emails and invoices)
              </span>
            </Label>

            {/* Current logo preview */}
            {form.companyLogoUrl && (
              <div className="flex items-center gap-3">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={form.companyLogoUrl}
                  alt="Logo preview"
                  className="h-14 max-w-[160px] object-contain rounded border border-[var(--border)] p-1.5 bg-white"
                />
                <button
                  type="button"
                  onClick={() => handleChange("companyLogoUrl", "")}
                  className="text-xs text-[var(--muted-foreground)] hover:text-[var(--destructive)] flex items-center gap-1"
                >
                  <X className="h-3 w-3" />
                  Remove
                </button>
              </div>
            )}

            {/* Upload button */}
            <div className="flex items-center gap-3">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={logoUploading}
                onClick={() => logoFileRef.current?.click()}
              >
                {logoUploading ? (
                  <Loader2 className="h-3 w-3 animate-spin mr-1" />
                ) : (
                  <Upload className="h-3 w-3 mr-1" />
                )}
                {form.companyLogoUrl ? "Replace logo" : "Upload logo"}
              </Button>
              <span className="text-xs text-[var(--muted-foreground)]">or paste a URL below</span>
            </div>
            <input
              ref={logoFileRef}
              type="file"
              accept="image/png,image/jpeg,image/svg+xml,image/webp"
              className="hidden"
              onChange={handleLogoUpload}
            />

            {/* URL fallback */}
            <Input
              value={form.companyLogoUrl}
              onChange={(e) => handleChange("companyLogoUrl", e.target.value)}
              placeholder="https://example.com/logo.png"
            />

            {logoError && (
              <p className="text-xs text-[var(--destructive)]">{logoError}</p>
            )}
          </div>
        </CardContent>
      </Card>
      </section>
      )}

      {activeSection === "email" && (
      <section className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Mail className="h-4 w-4" />Email delivery (SMTP)</CardTitle>
          <CardDescription>Outgoing mail for invoices, reminders, and receipts.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {smtpDeliveryReady ? (
            <Alert className="border-emerald-500/35 bg-emerald-500/10 text-emerald-950 dark:text-emerald-100 [&>svg]:text-emerald-700">
              <CheckCircle className="h-4 w-4" />
              <AlertDescription>
                SMTP delivery looks ready: host, port, from address, and saved password are in place (after Save, outbound mail uses these settings).
              </AlertDescription>
            </Alert>
          ) : (
            <Alert>
              <AlertDescription className="text-[var(--muted-foreground)]">
                Incomplete: set host, port, from address, and password - each shows <strong>Set · active</strong> when present. Password stays server-side until you replace it here.
              </AlertDescription>
            </Alert>
          )}
          <p className="text-xs text-[var(--muted-foreground)]">
            Encryption: TLS = STARTTLS (common on port 587), SSL = implicit TLS / SMTPS (common on 465), None = no encryption (local testing only).
          </p>
          <div className="grid grid-cols-3 gap-4">
            <div className="col-span-2 space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor="smtp-host">SMTP host</Label>
                <CredentialFieldBadge configured={form.smtpHost.trim().length > 0} />
              </div>
              <Input id="smtp-host" value={form.smtpHost} onChange={(e) => handleChange("smtpHost", e.target.value)} placeholder="smtp.gmail.com" autoComplete="off" />
            </div>
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor="smtp-port">Port</Label>
                <CredentialFieldBadge configured={Boolean(form.smtpPort?.toString().trim())} />
              </div>
              <Input id="smtp-port" type="number" value={form.smtpPort} onChange={(e) => handleChange("smtpPort", e.target.value)} placeholder="587" autoComplete="off" />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="smtp-encryption">Encryption</Label>
            <Select value={form.smtpEncryption} onValueChange={(v) => handleChange("smtpEncryption", v as SmtpEncryption)}>
              <SelectTrigger id="smtp-encryption" className="w-full max-w-md"><SelectValue placeholder="Encryption" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="TLS">TLS (STARTTLS)</SelectItem>
                <SelectItem value="SSL">SSL (SMTPS)</SelectItem>
                <SelectItem value="NONE">None</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor="smtp-user">Username</Label>
                <CredentialFieldBadge configured={form.smtpUser.trim().length > 0} />
              </div>
              <Input id="smtp-user" value={form.smtpUser} onChange={(e) => handleChange("smtpUser", e.target.value)} autoComplete="off" />
            </div>
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor="smtp-pass">Password</Label>
                <CredentialFieldBadge configured={cf.smtpPasswordSet} />
              </div>
              <Input
                id="smtp-pass"
                type="password"
                autoComplete="new-password"
                value={form.smtpPass}
                onChange={(e) => handleChange("smtpPass", e.target.value)}
                placeholder={
                  cf.smtpPasswordSet
                    ? `${KEYS_MASK_PLACEHOLDER} - type new password to replace`
                    : KEYS_MASK_PLACEHOLDER
                }
                spellCheck={false}
              />
            </div>
          </div>
          <div className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Label htmlFor="smtp-from">From address</Label>
              <CredentialFieldBadge configured={form.smtpFrom.trim().length > 0} />
            </div>
            <Input id="smtp-from" value={form.smtpFrom} onChange={(e) => handleChange("smtpFrom", e.target.value)} placeholder="noreply@company.com" autoComplete="off" />
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Legal footer (sent emails)</CardTitle>
          <CardDescription>
            Single line at the bottom of designed emails. Leave empty to build automatically from the Company tab: name,
            street, city, state/county, postal code, and country, comma-separated (empty fields are skipped). If nothing is
            set, a platform default line is used.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          <Textarea
            value={form.emailEnvelopeFooterLine}
            onChange={(e) => handleChange("emailEnvelopeFooterLine", e.target.value)}
            rows={2}
            placeholder="PUZZLE WEBSITE SOLUTIONS LLC, 15442 Ventura Blvd., Ste 201-039, Los Angeles, California, 91403, US"
            className="text-sm resize-y min-h-[72px]"
          />
          <div className="rounded-md border border-[var(--border)] bg-[var(--muted)]/30 px-3 py-3 text-center text-xs text-muted-foreground leading-relaxed">
            <p className="font-medium text-foreground">Effective line on sent emails</p>
            <p className="mt-1 break-words">{effectiveEmailFooterLegalLine}</p>
          </div>
        </CardContent>
      </Card>
      </section>
      )}

      {activeSection === "payments" && (
      <section className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CreditCard className="h-4 w-4" />
            Online payments - Stripe
          </CardTitle>
          <CardDescription>
            Prefer a Stripe{" "}
            <a
              href="https://docs.stripe.com/keys/restricted-api-keys"
              target="_blank"
              rel="noopener noreferrer"
              className="text-[var(--primary)] underline underline-offset-2"
            >
              restricted secret key
            </a>{" "}
            (rk_…) where possible. Add the webhook endpoint below in Stripe Dashboard → Developers → Webhooks, then paste the signing secret from that endpoint here.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2 rounded-lg border border-[var(--border)] bg-[var(--muted)]/40 p-4">
            <div className="flex flex-wrap items-end justify-between gap-2">
              <div className="space-y-1">
                <Label htmlFor="stripe-webhook-endpoint-url" className="text-sm font-medium">
                  Stripe webhook endpoint URL
                </Label>
                <p id="stripe-webhook-endpoint-hint" className="text-xs text-[var(--muted-foreground)]">
                  Use this exact URL when creating the endpoint (same account as your secret key). Path must include your org slug:{" "}
                  <code className="rounded bg-[var(--muted)] px-1 font-mono text-[11px]">{orgSlug}</code>
                </p>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0 gap-1.5"
                onClick={async () => {
                  const url =
                    stripeWebhookFullUrl ||
                    (typeof window !== "undefined"
                      ? `${window.location.origin}/api/stripe/webhook/${orgSlug}`
                      : "");
                  if (!url) return;
                  try {
                    await navigator.clipboard.writeText(url);
                    setStripeWebhookCopied(true);
                    window.setTimeout(() => setStripeWebhookCopied(false), 2000);
                  } catch {
                    setError("Could not copy to clipboard.");
                    window.setTimeout(() => setError(null), 4000);
                  }
                }}
              >
                {stripeWebhookCopied ? (
                  <>
                    <CheckCircle className="h-3.5 w-3.5 text-emerald-600" />
                    Copied
                  </>
                ) : (
                  <>
                    <Copy className="h-3.5 w-3.5" />
                    Copy URL
                  </>
                )}
              </Button>
            </div>
            <Input
              id="stripe-webhook-endpoint-url"
              readOnly
              aria-describedby="stripe-webhook-endpoint-hint"
              value={stripeWebhookFullUrl}
              placeholder={`https://your-public-domain.com/api/stripe/webhook/${orgSlug}`}
              className="font-mono text-xs"
              onFocus={(e) => e.currentTarget.select()}
            />
            <p className="text-xs text-[var(--muted-foreground)]">
              Uses <code className="rounded bg-[var(--muted)] px-1 font-mono text-[11px]">NEXT_PUBLIC_APP_URL</code> when set, otherwise the browser’s current origin. For production, set{" "}
              <code className="rounded bg-[var(--muted)] px-1 font-mono text-[11px]">NEXT_PUBLIC_APP_URL</code> to your live https URL so this matches Stripe.
            </p>
          </div>
          {stripePaymentsReady ? (
            <Alert className="border-emerald-500/35 bg-emerald-500/10 text-emerald-950 dark:text-emerald-100 [&>svg]:text-emerald-700">
              <CheckCircle className="h-4 w-4" />
              <AlertDescription>
                Stripe Checkout is configured: publishable key, secret key, and webhook secret are saved. Use Save after any change - keys are masked below.
              </AlertDescription>
            </Alert>
          ) : (
            <Alert>
              <AlertTriangle className="h-4 w-4 text-amber-600" />
              <AlertDescription className="text-[var(--muted-foreground)]">
                Add all three Stripe values for live payments - each shows <strong>Set · active</strong> when stored. Existing keys appear as bullets; type to replace them.
              </AlertDescription>
            </Alert>
          )}
          <div className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Label htmlFor="stripe-publishable">Publishable key</Label>
              <CredentialFieldBadge configured={cf.stripePublishableKeySet} />
            </div>
            <Input
              id="stripe-publishable"
              type="password"
              autoComplete="off"
              value={form.stripePublishableKey}
              onChange={(e) => handleChange("stripePublishableKey", e.target.value)}
              placeholder={
                cf.stripePublishableKeySet
                  ? `${KEYS_MASK_PLACEHOLDER} - type new key to replace`
                  : "pk_live_… / pk_test_…"
              }
              spellCheck={false}
              className="font-mono"
            />
          </div>
          <div className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Label htmlFor="stripe-secret">Secret key</Label>
              <CredentialFieldBadge configured={cf.stripeSecretKeySet} />
            </div>
            <Input
              id="stripe-secret"
              type="password"
              autoComplete="off"
              value={form.stripeSecretKey}
              onChange={(e) => handleChange("stripeSecretKey", e.target.value)}
              placeholder={
                cf.stripeSecretKeySet
                  ? `${KEYS_MASK_PLACEHOLDER} - type new rk_… / sk_… to replace`
                  : "rk_live_… / sk_live_… / rk_test_… / sk_test_…"
              }
              spellCheck={false}
              className="font-mono"
            />
          </div>
          <div className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Label htmlFor="stripe-webhook">Webhook signing secret</Label>
              <CredentialFieldBadge configured={cf.stripeWebhookSecretSet} />
            </div>
            <Input
              id="stripe-webhook"
              type="password"
              autoComplete="off"
              value={form.stripeWebhookSecret}
              onChange={(e) => handleChange("stripeWebhookSecret", e.target.value)}
              placeholder={
                cf.stripeWebhookSecretSet
                  ? `${KEYS_MASK_PLACEHOLDER} - type new whsec_… to replace`
                  : "whsec_…"
              }
              spellCheck={false}
              className="font-mono"
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Banknote className="h-4 w-4" />
            Bank / wire transfer
          </CardTitle>
          <CardDescription>Displayed when an invoice&apos;s payment method is Bank Transfer.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Bank name</Label>
              <Input value={form.bankName} onChange={(e) => handleChange("bankName", e.target.value)} placeholder="Chase Bank" />
            </div>
            <div className="space-y-2">
              <Label>Account holder</Label>
              <Input value={form.bankAccountHolder} onChange={(e) => handleChange("bankAccountHolder", e.target.value)} placeholder="Acme Inc." />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>IBAN / Account number</Label>
              <Input value={form.bankIban} onChange={(e) => handleChange("bankIban", e.target.value)} placeholder="GB29 NWBK 6016 1331 9268 19" />
            </div>
            <div className="space-y-2">
              <Label>BIC / Routing number</Label>
              <Input value={form.bankBic} onChange={(e) => handleChange("bankBic", e.target.value)} placeholder="NWBKGB2L" />
            </div>
          </div>
          <div className="space-y-2">
            <Label>Additional instructions</Label>
            <Textarea
              value={form.bankInstructions}
              onChange={(e) => handleChange("bankInstructions", e.target.value)}
              placeholder="Include invoice number as payment reference..."
              rows={3}
            />
          </div>
        </CardContent>
      </Card>
      </section>
      )}

      {activeSection === "invoices" && (
      <section className="space-y-4">
      {invoiceTemplatesCard}
      {invoiceNumberingCard}
      </section>
      )}

      {activeSection === "billing" && (
      <section className="space-y-4">
      {/* Billing */}
      <Card>
        <CardHeader>
          <CardTitle>Billing</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Display currency</Label>
              <Select value={form.displayCurrency} onValueChange={(v) => handleChange("displayCurrency", v as Currency)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{CURRENCIES.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Default VAT rate</Label>
              <Input type="number" step="0.01" min="0" max="100" value={form.vatRate} onChange={(e) => handleChange("vatRate", e.target.value)} placeholder="20" />
            </div>
          </div>
          <div className="space-y-2">
            <Label>Number format</Label>
            <Select value={form.numberFormatStyle} onValueChange={(v) => handleChange("numberFormatStyle", v)}>
              <SelectTrigger className="max-w-xs"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="COMMA_DOT">1,234.56</SelectItem>
                <SelectItem value="DOT_COMMA">1.234,56</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-[var(--muted-foreground)]">
              Applies to currency values across invoices, receipts, emails, tasks and billing.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Default due date</Label>
              <Select
                value={form.defaultDueDays}
                onValueChange={(v) => handleChange("defaultDueDays", v)}
              >
                <SelectTrigger><SelectValue placeholder="Set manually" /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">Set manually</SelectItem>
                  <SelectItem value="0">On receipt (0 days)</SelectItem>
                  <SelectItem value="7">Net 7 (7 days)</SelectItem>
                  <SelectItem value="14">Net 14 (14 days)</SelectItem>
                  <SelectItem value="30">Net 30 (30 days)</SelectItem>
                  <SelectItem value="60">Net 60 (60 days)</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="flex items-center justify-between">
            <div>
              <Label>Prices include VAT by default</Label>
              <p className="text-xs text-[var(--muted-foreground)]">New invoices will default to VAT-inclusive pricing. Can be overridden per invoice.</p>
            </div>
            <Switch checked={form.defaultVatIncluded} onCheckedChange={(v) => handleChange("defaultVatIncluded", v)} />
          </div>
          <div className="flex items-center justify-between">
            <div>
              <Label>Auto-send invoices</Label>
              <p className="text-xs text-[var(--muted-foreground)]">Automatically send invoices by email when created by auto-billing</p>
            </div>
            <Switch checked={form.autoSendInvoice} onCheckedChange={(v) => handleChange("autoSendInvoice", v)} />
          </div>
        </CardContent>
      </Card>

      {/* Default billing schedule */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Clock className="h-4 w-4" />Default billing schedule</CardTitle>
          <CardDescription>
            Set the default auto-invoice cycle for projects. Projects can override this individually.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Default cycle</Label>
            <Select
              value={form.defaultAutoInvoiceCycle}
              onValueChange={(v) => handleChange("defaultAutoInvoiceCycle", v as AutoInvoiceCycle)}
            >
              <SelectTrigger className="max-w-xs">
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

          {form.defaultAutoInvoiceCycle === "SEMI_MONTHLY" && (
            <div className="rounded-lg border border-[var(--border)] p-4 space-y-4 bg-[var(--muted)]/30">
              <p className="text-sm text-[var(--muted-foreground)]">
                Each month is split into two billing periods. Configure the split point and the day each invoice is emitted.
              </p>
              <div className="grid grid-cols-3 gap-4">
                <div className="space-y-2">
                  <Label>Period split day</Label>
                  <Input
                    type="number"
                    min={1}
                    max={28}
                    value={form.semiMonthlyPeriodSplitDay}
                    onChange={(e) => setForm((f) => ({ ...f, semiMonthlyPeriodSplitDay: parseInt(e.target.value) || 15 }))}
                  />
                  <p className="text-xs text-[var(--muted-foreground)]">Period 1 covers 1st–{form.semiMonthlyPeriodSplitDay}</p>
                </div>
                <div className="space-y-2">
                  <Label>Emit invoice 1 on day</Label>
                  <Input
                    type="number"
                    min={1}
                    max={31}
                    value={form.semiMonthlyEmitDay1}
                    onChange={(e) => setForm((f) => ({ ...f, semiMonthlyEmitDay1: parseInt(e.target.value) || 16 }))}
                  />
                  <p className="text-xs text-[var(--muted-foreground)]">Invoice for days 1–{form.semiMonthlyPeriodSplitDay}</p>
                </div>
                <div className="space-y-2">
                  <Label>Emit invoice 2 on day</Label>
                  <Input
                    type="number"
                    min={1}
                    max={31}
                    value={form.semiMonthlyEmitDay2}
                    onChange={(e) => setForm((f) => ({ ...f, semiMonthlyEmitDay2: parseInt(e.target.value) || 1 }))}
                  />
                  <p className="text-xs text-[var(--muted-foreground)]">Invoice for days {form.semiMonthlyPeriodSplitDay + 1}–end of month</p>
                </div>
              </div>
              <div className="rounded-md bg-[var(--muted)] px-3 py-2 text-xs text-[var(--muted-foreground)] font-mono space-y-1">
                <p>Period 1: 1st → {form.semiMonthlyPeriodSplitDay}th - invoice emitted on the {form.semiMonthlyEmitDay1}{ordinal(form.semiMonthlyEmitDay1)} at 9:00 AM</p>
                <p>Period 2: {form.semiMonthlyPeriodSplitDay + 1}th → end of month - invoice emitted on the {form.semiMonthlyEmitDay2}{ordinal(form.semiMonthlyEmitDay2)} at 9:00 AM</p>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* FX Rates */}
      <Card>
        <CardHeader>
          <CardTitle>FX Rates</CardTitle>
          <CardDescription>Exchange rates used for multi-currency invoicing (base: EUR)</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {fxRates.length > 0 ? (
            <div className="grid grid-cols-2 gap-2 text-sm">
              {fxRates.map((r) => (
                <div key={r.quoteCurrency} className="flex justify-between rounded border border-[var(--border)] px-3 py-2">
                  <span className="font-medium">EUR/{r.quoteCurrency}</span>
                  <span>{Number(r.rate).toFixed(4)}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-[var(--muted-foreground)]">No FX rates synced yet.</p>
          )}
          {fxRates[0] && (
            <p className="text-xs text-[var(--muted-foreground)]">Last updated: {formatDate(fxRates[0].fetchedAt)}</p>
          )}
          <Button type="button" variant="outline" onClick={handleSyncFx} disabled={syncing}>
            {syncing ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            Sync rates
          </Button>
        </CardContent>
      </Card>
      </section>
      )}

      {activeSection === "privacy" && (
      <section className="space-y-4">
      {/* Privacy & Compliance */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Shield className="h-4 w-4" />Privacy &amp; Compliance</CardTitle>
          <CardDescription>Enable compliance modes based on the privacy laws applicable to your customers. Both can be active simultaneously (e.g. a US company with EU clients).</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <Label>EU / UK GDPR mode</Label>
              <p className="text-xs text-[var(--muted-foreground)] mt-0.5">Enables <strong>right-to-erasure</strong> (Art. 17) workflow. Anonymises customer PII while retaining invoices and business records. Response deadline: 30 days.</p>
            </div>
            <Switch checked={form.privacyGdpr} onCheckedChange={(v) => handleChange("privacyGdpr", v)} />
          </div>
          <div className="flex items-start justify-between gap-4">
            <div>
              <Label>US CCPA / CPRA mode</Label>
              <p className="text-xs text-[var(--muted-foreground)] mt-0.5">Enables <strong>right-to-delete</strong> and <strong>right-to-know</strong> (data export) workflows for California residents. Business records retained under CCPA exceptions. Response deadline: 45 days.</p>
            </div>
            <Switch checked={form.privacyCcpa} onCheckedChange={(v) => handleChange("privacyCcpa", v)} />
          </div>
          {(form.privacyGdpr || form.privacyCcpa) && (
            <Alert>
              <Shield className="h-4 w-4" />
              <AlertDescription className="text-xs">
                When compliance mode is active, customer deletion is blocked if business records exist.
                Use the <strong>Anonymise</strong> or <strong>Delete personal data</strong> actions on the customer page instead.
              </AlertDescription>
            </Alert>
          )}
        </CardContent>
      </Card>
      </section>
      )}

      {activeSection === "time" && (
      <section className="space-y-4">
      {/* Time Tracking */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Clock className="h-4 w-4" />Time Tracking</CardTitle>
          <CardDescription>
            Automatically round time entries when a timer is stopped or a manual entry is saved.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Time rounding</Label>
            <Select value={form.timeRounding} onValueChange={(v) => handleChange("timeRounding", v)}>
              <SelectTrigger className="w-full max-w-xs">
                <SelectValue placeholder="No rounding" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="NONE">No rounding</SelectItem>
                <SelectItem value="UP_30">Round up to nearest 30 min</SelectItem>
                <SelectItem value="UP_60">Round up to nearest 1 hour</SelectItem>
                <SelectItem value="DOWN_30">Round down to nearest 30 min</SelectItem>
                <SelectItem value="DOWN_60">Round down to nearest 1 hour</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-[var(--muted-foreground)]">
              Applied when stopping a timer or saving a manual entry. &quot;Round down&quot; keeps a minimum of one increment so very short entries are not lost.
            </p>
          </div>
        </CardContent>
      </Card>
      </section>
      )}

      </div>

      <Button type="submit" disabled={saving}>
        {saving && <Loader2 className="h-4 w-4 animate-spin" />}
        Save settings
      </Button>
    </form>
    </div>
  );
}
