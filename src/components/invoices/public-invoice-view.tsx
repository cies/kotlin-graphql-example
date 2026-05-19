"use client";

import { useState } from "react";
import type { PaymentMethod, InvoiceTemplate } from "@prisma/client";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { CheckCircle, AlertTriangle, Download, CreditCard, Building2, Copy, Check } from "lucide-react";
import { createPublicCheckoutSession } from "@/lib/actions/payments";
import { formatCurrency, normalizeNumberFormatStyle, type NumberFormatStyle } from "@/lib/utils/format";
import {
  InvoicePublicAttachments,
  type PublicAttachmentRow,
} from "@/components/invoices/invoice-public-attachments";

interface InvoiceLine {
  name: string;
  description?: string;
  quantity: string;
  qtyType?: string;
  unitPrice: string;
  total: string;
}

interface BankDetails {
  bankName?: string;
  bankIban?: string;
  bankBic?: string;
  bankAccountHolder?: string;
  bankInstructions?: string;
}

interface PublicInvoiceData {
  id: string;
  number: string;
  status: string;
  currency: string;
  issuedAt: string;
  dueDate: string | null;
  subtotal: string;
  vat: string;
  total: string;
  amountPaid: string;
  amountDue: string;
  notes?: string;
  termsAndConditions?: string | null;
  paymentMethod: PaymentMethod | null;
  template: InvoiceTemplate | null;
  vatIncluded?: boolean;
  vatRate?: number;
  periodFrom?: string | null;
  periodTo?: string | null;
  discount?: string;
  discountType?: string;
  discountValue?: string;
  discountBeforeTax?: boolean;
}

interface Props {
  token: string;
  orgName: string;
  orgAddress?: string;
  orgVat?: string;
  orgLogoUrl?: string;
  customerName: string;
  customerEmail?: string;
  customerPhone?: string;
  customerVat?: string;
  invoice: PublicInvoiceData;
  lines: InvoiceLine[];
  bankDetails?: BankDetails;
  hasStripe: boolean;
  accentColor?: string;
  paymentStatus?: "success" | "cancelled";
  numberFormatStyle?: NumberFormatStyle;
  /** When false, Stripe / bank payment UI is hidden (e.g. staff preview). Default true. */
  showCustomerPayment?: boolean;
  /** Compact layout without full-page shell (staff invoice tab). */
  embedded?: boolean;
  /** When provided, shows attachment list + public upload (token URL only). */
  publicAttachments?: PublicAttachmentRow[];
  /** Staff-embedded: PDF/download uses authenticated preview route (no view token). */
  useSessionPdf?: boolean;
}

function fmtCurrency(amount: string, currency: string, numberFormatStyle?: NumberFormatStyle): string {
  return formatCurrency(
    amount,
    currency as Parameters<typeof formatCurrency>[1],
    normalizeNumberFormatStyle(numberFormatStyle)
  );
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={() => { navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 2000); }}
      className="ml-2 inline-flex items-center text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]"
    >
      {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
    </button>
  );
}

export function PublicInvoiceView({
  token,
  orgName,
  orgAddress,
  orgVat,
  orgLogoUrl,
  customerName,
  customerEmail,
  customerPhone,
  customerVat,
  invoice,
  lines,
  bankDetails,
  hasStripe,
  accentColor,
  paymentStatus,
  numberFormatStyle,
  showCustomerPayment = true,
  embedded = false,
  publicAttachments,
  useSessionPdf = false,
}: Props) {
  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState<string | null>(null);
  const accent = accentColor ?? "#2563eb";

  const canPay =
    showCustomerPayment &&
    !["PAID", "VOID", "REFUNDED", "CHARGEBACK"].includes(invoice.status) &&
    parseFloat(invoice.amountDue) > 0;
  const showStripe = canPay && hasStripe && (!invoice.paymentMethod || invoice.paymentMethod === "STRIPE");
  const showBank = canPay && invoice.paymentMethod === "BANK_TRANSFER" && bankDetails;
  const displayStatus = invoice.status === "SENT" ? "UNPAID" : invoice.status;

  const pdfDownloadHref = useSessionPdf
    ? `/api/pdf/invoice/${invoice.id}?preview=1`
    : `/api/pdf/invoice/${invoice.id}?token=${encodeURIComponent(token)}`;

  async function handleStripePayment() {
    setPaying(true);
    setPayError(null);
    try {
      const result = await createPublicCheckoutSession(token);
      if (result.url) {
        window.location.href = result.url;
      } else {
        setPayError(result.error ?? "Payment session could not be created");
      }
    } catch {
      setPayError("An unexpected error occurred");
    } finally {
      setPaying(false);
    }
  }

  return (
    <div className={embedded ? "w-full" : "min-h-screen bg-[#f8fafc]"}>
      {!embedded && <div className="h-1" style={{ background: accent }} />}

      <div className={embedded ? "w-full" : "max-w-3xl mx-auto px-4 py-12"}>

        {/* Payment status banners */}
        {paymentStatus === "success" && (
          <Alert className="mb-6 border-green-200 bg-green-50">
            <CheckCircle className="h-4 w-4 text-green-600" />
            <AlertDescription className="text-green-800">
              Payment received - thank you! Your receipt will be emailed to you shortly.
            </AlertDescription>
          </Alert>
        )}
        {paymentStatus === "cancelled" && (
          <Alert className="mb-6 border-amber-200 bg-amber-50">
            <AlertTriangle className="h-4 w-4 text-amber-600" />
            <AlertDescription className="text-amber-800">
              Payment was cancelled. You can try again below.
            </AlertDescription>
          </Alert>
        )}

        {/* Invoice card */}
        <div className="bg-white rounded-xl shadow-sm border border-[var(--border)] overflow-hidden">
          {/* Invoice header */}
          <div className="px-8 py-6 border-b border-[var(--border)]">
            <div className="flex justify-between items-start">
              <div>
                {orgLogoUrl && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={orgLogoUrl} alt={orgName} className="h-12 object-contain mb-2" />
                )}
                <div className="font-bold text-lg">{orgName}</div>
                {orgAddress && <div className="text-sm text-[var(--muted-foreground)]">{orgAddress}</div>}
                {orgVat && <div className="text-sm text-[var(--muted-foreground)]">VAT: {orgVat}</div>}
              </div>
              <div className="text-right">
                <div className="text-2xl font-bold text-foreground">INVOICE</div>
                <div className="text-sm text-[var(--muted-foreground)] mt-1">{invoice.number}</div>
                <span className={`inline-block mt-2 text-xs px-2 py-1 rounded-full font-medium ${
                  invoice.status === "PAID" ? "bg-green-100 text-green-800" :
                  invoice.status === "OVERDUE" ? "bg-red-100 text-red-800" :
                  invoice.status === "SENT" ? "bg-blue-100 text-blue-800" :
                  invoice.status === "REFUNDED" ? "bg-orange-100 text-orange-900" :
                  invoice.status === "CHARGEBACK" ? "bg-red-100 text-red-900" :
                  "bg-gray-100 text-gray-700"
                }`}>{displayStatus}</span>
              </div>
            </div>
          </div>

          {/* Metadata row */}
          <div className="grid grid-cols-3 gap-0 border-b border-[var(--border)]">
            <div className="px-8 py-4 border-r border-[var(--border)]">
              <div className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Bill To</div>
              <div className="font-medium text-sm">{customerName}</div>
              {customerEmail && <div className="text-xs text-[var(--muted-foreground)]">{customerEmail}</div>}
              {customerPhone && (
                <div className="text-xs text-[var(--muted-foreground)]">{customerPhone}</div>
              )}
              {customerVat && <div className="text-xs text-[var(--muted-foreground)]">VAT: {customerVat}</div>}
            </div>
            <div className="px-8 py-4 border-r border-[var(--border)]">
              <div className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Issue Date</div>
              <div className="text-sm font-medium">{fmtDate(invoice.issuedAt)}</div>
            </div>
            <div className="px-8 py-4">
              <div className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Due Date</div>
              <div className="text-sm font-medium">{fmtDate(invoice.dueDate)}</div>
              {(invoice.periodFrom || invoice.periodTo) && (
                <div className="mt-3">
                  <div className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Invoiced Period</div>
                  <div className="text-sm font-medium">
                    {fmtDate(invoice.periodFrom)} – {fmtDate(invoice.periodTo)}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Line items */}
          <div className="px-8 py-6">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-[#bfdbfe] bg-[#dbeafe] text-sm uppercase tracking-wide text-[#1e3a8a]">
                  <th className="text-left py-2 pl-0 pr-2">Item / Service</th>
                  <th className="text-right py-2">Qty</th>
                  <th className="text-right py-2">Unit Price</th>
                  <th className="text-right py-2">Total</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, i) => {
                  const qtyNum = parseFloat(line.quantity);
                  const qtyDisplay =
                    line.qtyType === "HOURS" || line.qtyType === "QTY_HOURS"
                      ? `${qtyNum.toFixed(2)} hrs`
                      : qtyNum % 1 === 0 ? String(Math.round(qtyNum)) : String(qtyNum);
                  return (
                  <tr key={i} className="border-b border-[#f3f4f6]">
                    <td className="py-3">
                      <span className="font-medium">{line.name}</span>
                      {line.description && (
                        <span className="block text-xs text-[var(--muted-foreground)] mt-0.5">{line.description}</span>
                      )}
                    </td>
                    <td className="text-right py-3 text-[var(--muted-foreground)] tabular-nums">{qtyDisplay}</td>
                    <td className="text-right py-3 text-[var(--muted-foreground)]">{fmtCurrency(line.unitPrice, invoice.currency, numberFormatStyle)}</td>
                    <td className="text-right py-3 font-medium">{fmtCurrency(line.total, invoice.currency, numberFormatStyle)}</td>
                  </tr>
                  );
                })}
              </tbody>
            </table>

            {/* Totals */}
            <div className="flex justify-end mt-4">
              <div className="w-64 space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-[var(--muted-foreground)]">Subtotal</span>
                  <span>{fmtCurrency(invoice.subtotal, invoice.currency, numberFormatStyle)}</span>
                </div>
                {invoice.discountType && invoice.discountType !== "NONE" && parseFloat(invoice.discount ?? "0") > 0 && invoice.discountBeforeTax && (
                  <div className="flex justify-between text-green-700">
                    <span>Discount {invoice.discountType === "PERCENTAGE" ? `(${parseFloat(invoice.discountValue ?? "0").toFixed(1)}%)` : "(fixed)"}</span>
                    <span>-{fmtCurrency(invoice.discount!, invoice.currency, numberFormatStyle)}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span className="text-[var(--muted-foreground)]">
                    VAT ({((invoice.vatRate ?? 0) * 100).toFixed(1)}%{invoice.vatIncluded ? " incl." : ""})
                  </span>
                  <span>{fmtCurrency(invoice.vat, invoice.currency, numberFormatStyle)}</span>
                </div>
                {invoice.discountType && invoice.discountType !== "NONE" && parseFloat(invoice.discount ?? "0") > 0 && !invoice.discountBeforeTax && (
                  <div className="flex justify-between text-green-700">
                    <span>Discount {invoice.discountType === "PERCENTAGE" ? `(${parseFloat(invoice.discountValue ?? "0").toFixed(1)}% after tax)` : "(fixed, after tax)"}</span>
                    <span>-{fmtCurrency(invoice.discount!, invoice.currency, numberFormatStyle)}</span>
                  </div>
                )}
                {parseFloat(invoice.amountPaid) > 0 && (
                  <div className="flex justify-between">
                    <span className="text-[var(--muted-foreground)]">Amount Paid</span>
                    <span>{fmtCurrency(invoice.amountPaid, invoice.currency, numberFormatStyle)}</span>
                  </div>
                )}
                <div className="flex justify-between font-bold text-base border-t border-[var(--border)] pt-2 text-foreground">
                  <span>{parseFloat(invoice.amountPaid) > 0 ? "Amount Due" : "Total"}</span>
                  <span>{fmtCurrency(parseFloat(invoice.amountPaid) > 0 ? invoice.amountDue : invoice.total, invoice.currency, numberFormatStyle)}</span>
                </div>
              </div>
            </div>

            {invoice.notes && (
              <div className="mt-6 pt-4 border-t border-[var(--border)]">
                <p className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Notes</p>
                <p className="text-sm text-[var(--muted-foreground)] whitespace-pre-wrap">{invoice.notes}</p>
              </div>
            )}

            {invoice.termsAndConditions && (
              <div className="mt-4 pt-4 border-t border-[var(--border)]">
                <p className="text-xs text-[var(--muted-foreground)] uppercase tracking-wide mb-1">Terms &amp; Conditions</p>
                <p className="text-sm text-[var(--muted-foreground)] whitespace-pre-wrap">{invoice.termsAndConditions}</p>
              </div>
            )}

            {publicAttachments != null && (
              <InvoicePublicAttachments
                token={token}
                status={invoice.status}
                attachments={publicAttachments}
              />
            )}
          </div>

          {/* PDF download */}
          <div className="px-8 pb-4">
            <a
              href={pdfDownloadHref}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 text-sm text-[var(--muted-foreground)] hover:text-[var(--foreground)] border border-[var(--border)] rounded-md px-3 py-1.5 transition-colors"
            >
              <Download className="h-3.5 w-3.5" />
              Download PDF
            </a>
          </div>
        </div>

        {/* Payment box */}
        {canPay && (
          <div className="mt-6">
            {payError && (
              <Alert variant="destructive" className="mb-4">
                <AlertDescription>{payError}</AlertDescription>
              </Alert>
            )}

            {showStripe && (
              <div className="bg-white rounded-xl shadow-sm border border-[var(--border)] p-6">
                <h2 className="font-semibold text-base mb-1 flex items-center gap-2">
                  <CreditCard className="h-4 w-4" style={{ color: accent }} />
                  Pay by card
                </h2>
                <p className="text-sm text-[var(--muted-foreground)] mb-4">
                  Secure payment via Stripe - {fmtCurrency(invoice.amountDue, invoice.currency, numberFormatStyle)} due.
                </p>
                <Button onClick={handleStripePayment} disabled={paying} style={{ background: accent }}>
                  {paying ? "Redirecting…" : `Pay ${fmtCurrency(invoice.amountDue, invoice.currency, numberFormatStyle)}`}
                </Button>
              </div>
            )}

            {showBank && bankDetails && (
              <div className="bg-white rounded-xl shadow-sm border border-[var(--border)] p-6">
                <h2 className="font-semibold text-base mb-1 flex items-center gap-2">
                  <Building2 className="h-4 w-4" style={{ color: accent }} />
                  Bank / wire transfer
                </h2>
                <p className="text-sm text-[var(--muted-foreground)] mb-4">
                  Please transfer {fmtCurrency(invoice.amountDue, invoice.currency, numberFormatStyle)} to the account below. Include the invoice number as payment reference.
                </p>
                <div className="bg-[#f8fafc] rounded-lg p-4 space-y-2 text-sm">
                  {bankDetails.bankAccountHolder && (
                    <div className="flex justify-between items-center">
                      <span className="text-[var(--muted-foreground)]">Account holder</span>
                      <span className="font-medium">{bankDetails.bankAccountHolder}<CopyButton text={bankDetails.bankAccountHolder} /></span>
                    </div>
                  )}
                  {bankDetails.bankName && (
                    <div className="flex justify-between items-center">
                      <span className="text-[var(--muted-foreground)]">Bank</span>
                      <span className="font-medium">{bankDetails.bankName}</span>
                    </div>
                  )}
                  {bankDetails.bankIban && (
                    <div className="flex justify-between items-center">
                      <span className="text-[var(--muted-foreground)]">IBAN / Account</span>
                      <span className="font-medium font-mono">{bankDetails.bankIban}<CopyButton text={bankDetails.bankIban} /></span>
                    </div>
                  )}
                  {bankDetails.bankBic && (
                    <div className="flex justify-between items-center">
                      <span className="text-[var(--muted-foreground)]">BIC / Routing</span>
                      <span className="font-medium font-mono">{bankDetails.bankBic}<CopyButton text={bankDetails.bankBic} /></span>
                    </div>
                  )}
                  <div className="flex justify-between items-center pt-1 border-t border-[var(--border)]">
                    <span className="text-[var(--muted-foreground)]">Reference</span>
                    <span className="font-medium">{invoice.number}<CopyButton text={invoice.number} /></span>
                  </div>
                </div>
                {bankDetails.bankInstructions && (
                  <p className="text-xs text-[var(--muted-foreground)] mt-3 whitespace-pre-wrap">{bankDetails.bankInstructions}</p>
                )}
              </div>
            )}

            {!showStripe && !showBank && (
              <div className="bg-white rounded-xl shadow-sm border border-[var(--border)] p-6 text-center text-sm text-[var(--muted-foreground)]">
                To pay this invoice, please contact {orgName} directly.
              </div>
            )}
          </div>
        )}

        {invoice.status === "PAID" && (
          <div className="mt-6 bg-green-50 border border-green-200 rounded-xl p-6 text-center">
            <CheckCircle className="h-8 w-8 text-green-600 mx-auto mb-2" />
            <div className="font-semibold text-green-800">Invoice fully paid</div>
            <div className="text-sm text-green-700 mt-1">Thank you for your payment!</div>
          </div>
        )}

        <p className="text-center text-xs text-[var(--muted-foreground)] mt-8">{orgName}</p>
      </div>
    </div>
  );
}
