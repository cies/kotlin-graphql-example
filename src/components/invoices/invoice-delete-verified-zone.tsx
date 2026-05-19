"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Trash2, Loader2, Mail } from "lucide-react";
import {
  requestInvoiceDeletionVerificationCode,
  deleteInvoiceWithVerificationCode,
} from "@/lib/actions/invoices";

export function InvoiceDeleteVerifiedZone({
  orgSlug,
  invoiceId,
  invoiceNumber,
  smtpConfigured,
}: {
  orgSlug: string;
  invoiceId: string;
  invoiceNumber: string;
  smtpConfigured: boolean;
}) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [requesting, setRequesting] = useState(false);
  const [deleting, setDeleting] = useState(false);

  function handleOpenChange(next: boolean) {
    setOpen(next);
    if (!next) {
      setCode("");
    }
  }

  async function sendCode() {
    setRequesting(true);
    const res = await requestInvoiceDeletionVerificationCode(orgSlug, invoiceId);
    setRequesting(false);
    if ("error" in res && res.error) {
      toast.error(res.error);
    } else {
      toast.success("Verification code sent using your organization SMTP settings.");
    }
  }

  async function deleteNow() {
    setDeleting(true);
    const res = await deleteInvoiceWithVerificationCode(orgSlug, invoiceId, code);
    setDeleting(false);
    if ("error" in res && res.error) {
      toast.error(res.error);
    } else {
      toast.success(`Invoice ${invoiceNumber} deleted`);
      handleOpenChange(false);
      router.push(`/${orgSlug}/invoices`);
      router.refresh();
    }
  }

  const settingsHref = `/${orgSlug}/settings`;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="inline-flex items-center gap-2 border-[var(--destructive)]/50 text-[var(--destructive)] hover:bg-[var(--destructive)]/10"
        onClick={() => handleOpenChange(true)}
      >
        <Trash2 className="h-4 w-4" />
        Delete invoice…
      </Button>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-[var(--destructive)]">
            <Trash2 className="h-4 w-4 shrink-0" />
            Delete invoice {invoiceNumber}
          </DialogTitle>
          <DialogDescription className="text-left">
            A one-time code is sent to your sign-in email using the same SMTP configured in{" "}
            <Link href={settingsHref} className="font-medium text-foreground underline underline-offset-2">
              organization settings
            </Link>
            . Only owners and admins can complete this step.
          </DialogDescription>
        </DialogHeader>

        <Alert variant="destructive">
          <AlertDescription>
            This cannot be undone. Payments, email history, and links to this invoice will be removed. Time entries on this
            invoice are unbilled so you can invoice them again.
          </AlertDescription>
        </Alert>

        {!smtpConfigured ? (
          <Alert>
            <AlertDescription>
              SMTP is not fully configured for this organization. Add host, port, user, password, and From address in{" "}
              <Link href={settingsHref} className="font-medium underline underline-offset-2">
                Settings
              </Link>{" "}
              before you can receive a deletion code.
            </AlertDescription>
          </Alert>
        ) : null}

        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={sendCode} disabled={requesting || !smtpConfigured}>
              {requesting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Mail className="h-4 w-4" />}
              Send verification code to my email
            </Button>
          </div>
          <div className="space-y-2 max-w-xs">
            <Label htmlFor="del-code">6-digit code from email</Label>
            <Input
              id="del-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="000000"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
              maxLength={6}
              className="font-mono tracking-widest"
            />
          </div>
        </div>

        <DialogFooter className="gap-2 sm:gap-0">
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)}>
            Cancel
          </Button>
          <Button type="button" variant="destructive" onClick={deleteNow} disabled={deleting || code.length !== 6}>
            {deleting && <Loader2 className="h-4 w-4 animate-spin mr-2" />}
            Delete permanently
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
