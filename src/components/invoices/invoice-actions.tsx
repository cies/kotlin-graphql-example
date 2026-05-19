"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils/cn";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { PaymentForm } from "./payment-form";
import { toast } from "sonner";
import {
  Send,
  FileDown,
  CreditCard,
  Ban,
  Eye,
  Link2,
  Check,
  Mail,
  Copy,
  X,
} from "lucide-react";
import type { Currency, InvoiceStatus, PaymentMethod } from "@prisma/client";
import {
  defaultInvoiceRecipientKeys,
  type InvoiceRecipientOption,
} from "@/lib/invoices/invoice-email-recipients-options";

interface Props {
  invoiceId: string;
  orgSlug: string;
  status: InvoiceStatus;
  currency: Currency;
  amountDue: number;
  hasStripe: boolean;
  /** Billing email plus portal contacts; empty means email send is unavailable. */
  invoiceEmailRecipients: InvoiceRecipientOption[];
  onSend: (opts?: { to?: string[]; bcc?: string[] }) => Promise<{ error?: string }>;
  onVoid: () => Promise<{ error?: string }>;
  onRecordPayment: (data: {
    invoiceId: string;
    amount: string;
    currency: Currency;
    method: PaymentMethod;
    paidAt?: string;
    notes?: string;
  }) => Promise<{ error?: string }>;
  onStripeCheckout: () => Promise<{ url?: string; error?: string }>;
  onGetPreviewUrl?: () => Promise<{ url?: string; error?: string }>;
  onDuplicateAsDraft?: () => Promise<{ invoiceId?: string; error?: string }>;
}

const MAX_BCC = 5;

function isValidEmail(raw: string): boolean {
  const s = raw.trim();
  if (!s) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);
}

/** Merge chips + optional trailing draft for send; enforces max and validity. */
function mergeBccForSend(
  emails: string[],
  draft: string
): { ok: true; list: string[] } | { ok: false; error: string } {
  const d = draft.trim();
  if (!d) return { ok: true, list: emails };
  if (emails.length >= MAX_BCC) {
    return { ok: false, error: `At most ${MAX_BCC} BCC addresses.` };
  }
  if (!isValidEmail(d)) {
    return { ok: false, error: "Invalid email address in BCC field." };
  }
  if (emails.some((e) => e.toLowerCase() === d.toLowerCase())) {
    return { ok: true, list: emails };
  }
  return { ok: true, list: [...emails, d] };
}

function InvoiceBccChipInput({
  emails,
  onEmailsChange,
  draft,
  onDraftChange,
  disabled,
  id,
}: {
  emails: string[];
  onEmailsChange: (next: string[]) => void;
  draft: string;
  onDraftChange: (v: string) => void;
  disabled?: boolean;
  id: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDraftChange = (v: string) => {
    if (!v.includes(",")) {
      onDraftChange(v);
      return;
    }
    const segments = v.split(",");
    const tail = segments.pop() ?? "";
    let next = [...emails];
    for (const seg of segments) {
      const p = seg.trim();
      if (!p) continue;
      if (next.length >= MAX_BCC) {
        toast.error(`At most ${MAX_BCC} BCC addresses.`);
        break;
      }
      if (!isValidEmail(p)) {
        toast.error("Invalid email address.");
        continue;
      }
      if (!next.some((e) => e.toLowerCase() === p.toLowerCase())) {
        next = [...next, p];
      }
    }
    onEmailsChange(next);
    onDraftChange(tail);
  };

  const commitDraft = (opts?: { toastIfInvalid?: boolean }) => {
    const p = draft.trim();
    if (!p) return;
    if (emails.length >= MAX_BCC) {
      if (opts?.toastIfInvalid) toast.error(`At most ${MAX_BCC} BCC addresses.`);
      return;
    }
    if (!isValidEmail(p)) {
      if (opts?.toastIfInvalid) toast.error("Invalid email address.");
      return;
    }
    if (emails.some((e) => e.toLowerCase() === p.toLowerCase())) {
      onDraftChange("");
      return;
    }
    onEmailsChange([...emails, p]);
    onDraftChange("");
  };

  return (
    <div
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) inputRef.current?.focus();
      }}
      className={cn(
        "flex min-h-[4.5rem] w-full flex-wrap gap-1.5 rounded-md border border-[var(--input)] bg-transparent px-2 py-2 text-sm shadow-sm transition-colors",
        "focus-within:ring-1 focus-within:ring-[var(--ring)]",
        disabled && "pointer-events-none opacity-50"
      )}
    >
      {emails.map((email) => (
        <span
          key={email}
          className="inline-flex max-w-full cursor-default items-center gap-0.5 rounded-md border border-[var(--input)] bg-[var(--muted)]/40 px-2 py-0.5 pl-2 text-xs text-foreground"
        >
          <span className="truncate">{email}</span>
          <button
            type="button"
            disabled={disabled}
            className="shrink-0 rounded p-0.5 text-[var(--muted-foreground)] hover:bg-[var(--accent)] hover:text-[var(--accent-foreground)]"
            aria-label={`Remove ${email}`}
            onClick={() => onEmailsChange(emails.filter((e) => e !== email))}
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </span>
      ))}
      <input
        ref={inputRef}
        id={id}
        type="text"
        name="invoice-bcc"
        inputMode="email"
        autoComplete="off"
        disabled={disabled || emails.length >= MAX_BCC}
        placeholder={
          emails.length >= MAX_BCC
            ? "Maximum addresses reached"
            : "Type an email, separate with comma…"
        }
        aria-label="BCC email, comma-separated"
        className="min-w-[12ch] flex-1 bg-transparent py-0.5 text-sm outline-none placeholder:text-[var(--muted-foreground)] disabled:cursor-not-allowed"
        value={draft}
        onChange={(e) => handleDraftChange(e.target.value)}
        onBlur={() => commitDraft()}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            commitDraft({ toastIfInvalid: true });
          }
          if (e.key === "Backspace" && !draft && emails.length > 0) {
            onEmailsChange(emails.slice(0, -1));
          }
        }}
      />
    </div>
  );
}

function CopyLinkButton({
  onGetPreviewUrl,
}: {
  onGetPreviewUrl: () => Promise<{ url?: string; error?: string }>;
}) {
  const [state, setState] = useState<"idle" | "loading" | "copied" | "error">("idle");

  const handleCopy = async () => {
    setState("loading");
    try {
      const res = await onGetPreviewUrl();
      if (res.url) {
        await navigator.clipboard.writeText(res.url);
        setState("copied");
        setTimeout(() => setState("idle"), 2200);
      } else {
        setState("error");
        setTimeout(() => setState("idle"), 2200);
      }
    } catch {
      setState("error");
      setTimeout(() => setState("idle"), 2200);
    }
  };

  return (
    <Button
      variant="outline"
      size="sm"
      onClick={handleCopy}
      disabled={state === "loading"}
    >
      {state === "copied" ? (
        <>
          <Check className="h-4 w-4 text-green-600" />
          Copied!
        </>
      ) : state === "error" ? (
        <>
          <Link2 className="h-4 w-4 text-red-500" />
          Failed
        </>
      ) : state === "loading" ? (
        "Copying…"
      ) : (
        <>
          <Link2 className="h-4 w-4" />
          Copy Link
        </>
      )}
    </Button>
  );
}

export function InvoiceActions({
  invoiceId,
  orgSlug,
  status,
  currency,
  amountDue,
  hasStripe,
  invoiceEmailRecipients,
  onSend,
  onVoid,
  onRecordPayment,
  onStripeCheckout,
  onGetPreviewUrl,
  onDuplicateAsDraft,
}: Props) {
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [voiding, setVoiding] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  const [voidOpen, setVoidOpen] = useState(false);
  const [duplicating, setDuplicating] = useState(false);

  const [sendDialogOpen, setSendDialogOpen] = useState(false);
  const [bccEmails, setBccEmails] = useState<string[]>([]);
  const [bccDraft, setBccDraft] = useState("");
  const [selectedRecipientKeys, setSelectedRecipientKeys] = useState<Set<string>>(new Set());

  const canSendOrResendEmail =
    invoiceEmailRecipients.length > 0 && status !== "VOID";

  const handleSendDialogOpenChange = (open: boolean) => {
    setSendDialogOpen(open);
    if (open && invoiceEmailRecipients.length > 0) {
      setSelectedRecipientKeys(defaultInvoiceRecipientKeys(invoiceEmailRecipients));
    }
    if (!open) {
      setBccEmails([]);
      setBccDraft("");
    }
  };

  const toggleRecipientKey = (key: string, checked: boolean) => {
    setSelectedRecipientKeys((prev) => {
      const next = new Set(prev);
      if (checked) next.add(key);
      else next.delete(key);
      return next;
    });
  };

  const handleSendConfirm = async () => {
    const emails: string[] = [];
    const seenLower = new Set<string>();
    for (const row of invoiceEmailRecipients) {
      if (!selectedRecipientKeys.has(row.key)) continue;
      const lc = row.email.trim().toLowerCase();
      if (seenLower.has(lc)) continue;
      seenLower.add(lc);
      emails.push(row.email.trim());
    }
    if (emails.length === 0) {
      toast.error("Select at least one recipient.");
      return;
    }
    const merged = mergeBccForSend(bccEmails, bccDraft);
    if (!merged.ok) {
      toast.error(merged.error);
      return;
    }
    setSending(true);
    const payload: { to: string[]; bcc?: string[] } = {
      to: emails,
    };
    if (merged.list.length) payload.bcc = merged.list;
    const res = await onSend(payload);
    setSending(false);
    if (res.error) {
      toast.error(res.error);
    } else {
      toast.success(status === "DRAFT" ? "Invoice sent" : "Invoice resent");
      handleSendDialogOpenChange(false);
    }
  };

  const handleVoidConfirmed = async () => {
    setVoidOpen(false);
    setVoiding(true);
    const res = await onVoid();
    setVoiding(false);
    if (res.error) {
      toast.error(res.error);
    } else {
      toast.success("Invoice voided");
    }
  };

  const handleStripeCheckout = async () => {
    setCheckingOut(true);
    const res = await onStripeCheckout();
    setCheckingOut(false);
    if (res.error) {
      toast.error(res.error);
    } else if (res.url) {
      window.location.href = res.url;
    }
  };

  const isPaid =
    status === "PAID" ||
    status === "VOID" ||
    status === "REFUNDED" ||
    status === "CHARGEBACK";

  const handleDuplicate = async () => {
    if (!onDuplicateAsDraft) return;
    setDuplicating(true);
    const res = await onDuplicateAsDraft();
    setDuplicating(false);
    if (res.error) {
      toast.error(res.error);
    } else if (res.invoiceId) {
      toast.success("Draft created");
      router.push(`/${orgSlug}/invoices/${res.invoiceId}`);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {/* Preview inline in new tab */}
        <Button variant="outline" size="sm" asChild>
          <a
            href={`/api/pdf/invoice/${invoiceId}?preview=1`}
            target="_blank"
            rel="noopener noreferrer"
          >
            <Eye className="h-4 w-4" />
            Preview
          </a>
        </Button>

        <Button variant="outline" size="sm" asChild>
          <Link href={`/${orgSlug}/invoices/${invoiceId}/email-preview`}>
            <Mail className="h-4 w-4" />
            Email preview
          </Link>
        </Button>

        {/* Download */}
        <Button variant="outline" size="sm" asChild>
          <a href={`/api/pdf/invoice/${invoiceId}`} target="_blank" rel="noopener noreferrer">
            <FileDown className="h-4 w-4" />
            Download PDF
          </a>
        </Button>

        {/* Copy public link (non-draft only) */}
        {onGetPreviewUrl && status !== "DRAFT" && (
          <CopyLinkButton onGetPreviewUrl={onGetPreviewUrl} />
        )}

        {onDuplicateAsDraft && (
          <Button variant="outline" size="sm" onClick={handleDuplicate} disabled={duplicating}>
            <Copy className="h-4 w-4" />
            {duplicating ? "Copying…" : "Copy as draft"}
          </Button>
        )}

        {canSendOrResendEmail && (
          <Dialog open={sendDialogOpen} onOpenChange={handleSendDialogOpenChange}>
            <DialogTrigger asChild>
              <Button size="sm" type="button">
                <Send className="h-4 w-4" />
                {status === "DRAFT" ? "Send to customer" : "Resend invoice"}
              </Button>
            </DialogTrigger>
            <DialogContent className="max-h-[85vh] overflow-y-auto">
              <DialogHeader>
                <DialogTitle>{status === "DRAFT" ? "Send invoice" : "Resend invoice"}</DialogTitle>
                <DialogDescription>
                  {status === "DRAFT"
                    ? "Choose one or more recipients below (billing plus each portal contact email). Attachments and tracking apply to all selected addresses."
                    : "Send another copy from your organization SMTP. Select one or more recipients; opens are tracked per recipient when tracking is enabled."}
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-2">
                <div className="space-y-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <Label>To</Label>
                    <div className="flex gap-1.5">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs"
                        disabled={sending}
                        onClick={() =>
                          setSelectedRecipientKeys(
                            new Set(invoiceEmailRecipients.map((r) => r.key))
                          )
                        }
                      >
                        Select all
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs"
                        disabled={sending}
                        onClick={() => setSelectedRecipientKeys(new Set())}
                      >
                        Clear
                      </Button>
                    </div>
                  </div>
                  <div className="max-h-[200px] space-y-2 overflow-y-auto rounded-md border border-[var(--input)] px-3 py-2">
                    {invoiceEmailRecipients.map((r) => (
                      <label
                        key={r.key}
                        className="flex cursor-pointer items-start gap-2.5 text-sm leading-snug"
                      >
                        <Checkbox
                          className="mt-0.5"
                          checked={selectedRecipientKeys.has(r.key)}
                          disabled={sending}
                          onCheckedChange={(next) =>
                            toggleRecipientKey(r.key, next === true)
                          }
                        />
                        <span>
                          <span className="block font-medium text-foreground">{r.email}</span>
                          <span className="block text-xs text-[var(--muted-foreground)]">
                            {r.label}
                          </span>
                        </span>
                      </label>
                    ))}
                  </div>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="invoice-bcc-input">BCC (optional, up to {MAX_BCC})</Label>
                  <InvoiceBccChipInput
                    id="invoice-bcc-input"
                    emails={bccEmails}
                    onEmailsChange={setBccEmails}
                    draft={bccDraft}
                    onDraftChange={setBccDraft}
                    disabled={sending}
                  />
                  <p className="text-xs text-[var(--muted-foreground)]">
                    BCC recipients are only included on the first outgoing message when multiple TO
                    addresses are selected (they will not receive duplicate blind copies).
                  </p>
                </div>
              </div>
              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => handleSendDialogOpenChange(false)}
                >
                  Cancel
                </Button>
                <Button type="button" onClick={handleSendConfirm} disabled={sending}>
                  {sending ? "Sending…" : status === "DRAFT" ? "Send now" : "Resend"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        )}

        {!isPaid && amountDue > 0 && (
          <>
            <PaymentForm
              invoiceId={invoiceId}
              currency={currency}
              amountDue={amountDue}
              onRecord={onRecordPayment}
            />

            {hasStripe && (
              <Button
                size="sm"
                variant="outline"
                onClick={handleStripeCheckout}
                disabled={checkingOut}
              >
                <CreditCard className="h-4 w-4" />
                {checkingOut ? "Redirecting..." : "Stripe Checkout"}
              </Button>
            )}
          </>
        )}

        {!["PAID", "VOID", "REFUNDED", "CHARGEBACK"].includes(status) && (
          <Button variant="outline" size="sm" onClick={() => setVoidOpen(true)} disabled={voiding}>
            <Ban className="h-4 w-4" />
            {voiding ? "Voiding..." : "Void"}
          </Button>
        )}
      </div>

      {invoiceEmailRecipients.length === 0 && status !== "VOID" && (
        <p className="text-xs text-[var(--muted-foreground)] max-w-xl">
          Cannot email this invoice: add a billing email or portal contacts with email addresses on the
          customer record.
        </p>
      )}

      <ConfirmDialog
        open={voidOpen}
        title="Void this invoice?"
        description="This cannot be undone. The invoice will be marked as void and no further payments can be recorded."
        confirmLabel="Void invoice"
        onConfirm={handleVoidConfirmed}
        onCancel={() => setVoidOpen(false)}
      />
    </div>
  );
}
