import { auth } from "@/lib/auth/auth";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getInvoiceEmailPreview } from "@/lib/actions/invoices";
import { ArrowLeft } from "lucide-react";

export const metadata = { title: "Invoice email preview" };

interface Props {
  params: Promise<{ orgSlug: string; invoiceId: string }>;
}

export default async function InvoiceEmailPreviewPage({ params }: Props) {
  const { orgSlug, invoiceId } = await params;
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") redirect("/auth/login");

  const result = await getInvoiceEmailPreview(orgSlug, invoiceId);
  if ("error" in result) {
    if (result.error === "Unauthorized") redirect("/auth/login");
    return (
      <div className="max-w-3xl mx-auto py-8 px-4 space-y-4">
        <Button variant="ghost" size="sm" asChild>
          <Link href={`/${orgSlug}/invoices/${invoiceId}`}>
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to invoice
          </Link>
        </Button>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Preview unavailable</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-destructive">{result.error}</p>
          </CardContent>
        </Card>
      </div>
    );
  }

  const { subject, html } = result;

  return (
    <div className="max-w-3xl mx-auto py-8 px-4 space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/invoices/${invoiceId}`} aria-label="Back to invoice">
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <h1 className="text-xl font-semibold flex-1">Email preview</h1>
      </div>
      <p className="text-sm text-[var(--muted-foreground)]">
        This is the composed customer email (subject + design wrapper) for the{" "}
        <code className="text-xs bg-[var(--muted)] px-1 rounded">invoice.sent</code> template, using this
        invoice&apos;s data. PDF is not attached in preview.
      </p>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Subject</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm font-medium">{subject}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Body</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <div className="rounded-md border border-[var(--border)] bg-white max-h-[min(80vh,900px)] overflow-auto">
            <div className="p-4 sm:p-6" dangerouslySetInnerHTML={{ __html: html }} />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
