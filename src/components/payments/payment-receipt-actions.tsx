"use client";

import { useTransition } from "react";
import Link from "next/link";
import { MoreHorizontal, FileText, Download, Mail, LayoutTemplate } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { resendPaymentReceipt } from "@/lib/actions/payments";

type Props = {
  orgSlug: string;
  paymentId: string;
  /** In-app page showing the same HTML as the email body (plus PDF actions). */
  receiptViewHref: string;
  /** Hide “Resend” (e.g. customer portal). Default true. */
  allowResend?: boolean;
};

export function PaymentReceiptActions({
  orgSlug,
  paymentId,
  receiptViewHref,
  allowResend = true,
}: Props) {
  const [pending, startTransition] = useTransition();

  const resend = () => {
    startTransition(async () => {
      const r = await resendPaymentReceipt(orgSlug, paymentId);
      if ("error" in r && r.error) {
        toast.error(r.error);
      } else {
        toast.success("Receipt sent to customer");
      }
    });
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label="Receipt actions">
          <MoreHorizontal className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem asChild>
          <Link href={receiptViewHref}>
            <LayoutTemplate className="mr-2 h-4 w-4" />
            Email-style preview
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <a
            href={`/api/pdf/receipt/payment/${paymentId}?preview=1`}
            target="_blank"
            rel="noopener noreferrer"
          >
            <FileText className="mr-2 h-4 w-4" />
            Open PDF
          </a>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <a
            href={`/api/pdf/receipt/payment/${paymentId}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            <Download className="mr-2 h-4 w-4" />
            Download PDF
          </a>
        </DropdownMenuItem>
        {allowResend && (
          <DropdownMenuItem disabled={pending} onClick={() => resend()}>
            <Mail className="mr-2 h-4 w-4" />
            {pending ? "Sending…" : "Resend to customer"}
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
