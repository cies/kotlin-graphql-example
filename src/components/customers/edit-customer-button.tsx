"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  deleteCustomer,
  gdprEraseCustomer,
  ccpaDeleteCustomer,
  ccpaExportCustomerData,
} from "@/lib/actions/customers";
import { Edit, Trash2, Loader2, ShieldAlert, Download } from "lucide-react";
import Link from "next/link";

interface Props {
  orgSlug: string;
  customer: { id: string; anonymizedAt?: Date | null };
  privacyGdpr?: boolean;
  privacyCcpa?: boolean;
  relatedCounts?: { invoices: number; projects: number; contracts: number } | null;
}

export function EditCustomerButton({
  orgSlug,
  customer,
  privacyGdpr = false,
  privacyCcpa = false,
  relatedCounts,
}: Props) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [privacyOpen, setPrivacyOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [erasing, setErasing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasRelated =
    relatedCounts && relatedCounts.invoices + relatedCounts.projects + relatedCounts.contracts > 0;
  const isAnonymized = !!customer.anonymizedAt;
  const showPrivacy = privacyGdpr || privacyCcpa;

  async function handleDelete() {
    setDeleting(true);
    setError(null);
    const res = await deleteCustomer(orgSlug, customer.id);
    if ("error" in res && res.error) {
      setError(res.error);
      setDeleting(false);
      return;
    }
    router.push(`/${orgSlug}/customers`);
  }

  async function handleGdprErase() {
    setErasing(true);
    setError(null);
    const res = await gdprEraseCustomer(orgSlug, customer.id);
    setErasing(false);
    if (res.error) {
      setError(res.error);
      return;
    }
    setPrivacyOpen(false);
    router.refresh();
  }

  async function handleCcpaDelete() {
    setErasing(true);
    setError(null);
    const res = await ccpaDeleteCustomer(orgSlug, customer.id);
    setErasing(false);
    if (res.error) {
      setError(res.error);
      return;
    }
    setPrivacyOpen(false);
    router.refresh();
  }

  async function handleCcpaExport() {
    setExporting(true);
    setError(null);
    const res = await ccpaExportCustomerData(orgSlug, customer.id);
    setExporting(false);
    if (res.error) {
      setError(res.error);
      return;
    }
    const blob = new Blob([JSON.stringify(res.data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `customer-data-${customer.id}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="flex gap-2">
      {!isAnonymized && (
        <Button variant="outline" asChild>
          <Link href={`/${orgSlug}/customers/${customer.id}/edit`}>
            <Edit className="h-4 w-4" />
            Edit
          </Link>
        </Button>
      )}

      {showPrivacy && (
        <Button
          variant="outline"
          className="text-amber-600 hover:text-amber-700"
          onClick={() => { setError(null); setPrivacyOpen(true); }}
          title="Privacy / data rights request"
        >
          <ShieldAlert className="h-4 w-4" />
          <span className="hidden sm:inline ml-1">Privacy</span>
        </Button>
      )}

      {!isAnonymized && (
        <Button
          variant="outline"
          className="text-[var(--destructive)] hover:text-[var(--destructive)]"
          onClick={() => { setError(null); setConfirmOpen(true); }}
          title={hasRelated ? `Has related records (${relatedCounts!.invoices} invoices, ${relatedCounts!.projects} projects, ${relatedCounts!.contracts} contracts)` : "Delete customer"}
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      )}

      {isAnonymized && (
        <div className="flex items-center gap-1 rounded-md border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs text-amber-700">
          <ShieldAlert className="h-3 w-3" />
          PII erased
        </div>
      )}

      {/* Delete confirmation dialog */}
      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete customer?</DialogTitle>
          </DialogHeader>

          {error ? (
            <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              {error}
            </div>
          ) : hasRelated ? (
            <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              <p className="font-medium">Cannot delete - related records exist:</p>
              <ul className="mt-1 list-disc pl-4 text-xs space-y-0.5">
                {relatedCounts!.invoices > 0 && <li>{relatedCounts!.invoices} invoice{relatedCounts!.invoices !== 1 ? "s" : ""}</li>}
                {relatedCounts!.projects > 0 && <li>{relatedCounts!.projects} project{relatedCounts!.projects !== 1 ? "s" : ""}</li>}
                {relatedCounts!.contracts > 0 && <li>{relatedCounts!.contracts} contract{relatedCounts!.contracts !== 1 ? "s" : ""}</li>}
              </ul>
              <p className="mt-2 text-xs">
                Remove those records first, or use the{" "}
                {showPrivacy ? (
                  <button
                    className="underline font-medium"
                    onClick={() => { setConfirmOpen(false); setPrivacyOpen(true); }}
                  >
                    Privacy erasure
                  </button>
                ) : (
                  "privacy erasure option (enable GDPR/CCPA in Settings)"
                )}{" "}
                to anonymise the customer while keeping business records.
              </p>
            </div>
          ) : (
            <p className="text-sm text-[var(--muted-foreground)]">
              This will permanently delete this customer and all associated data. This cannot be undone.
            </p>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            {!hasRelated && (
              <Button variant="destructive" onClick={handleDelete} disabled={deleting || !!error}>
                {deleting && <Loader2 className="h-4 w-4 animate-spin mr-1" />}
                Delete permanently
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Privacy / data rights dialog */}
      <Dialog open={privacyOpen} onOpenChange={setPrivacyOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ShieldAlert className="h-4 w-4 text-amber-600" />
              Privacy &amp; data rights
            </DialogTitle>
          </DialogHeader>

          <div className="space-y-4 text-sm text-[var(--muted-foreground)]">
            <p>
              These actions scrub all personally identifiable information (PII) from this customer
              while keeping associated business records (invoices, projects, contracts) intact for
              legal and accounting purposes.
            </p>

            {error && (
              <div className="rounded-md border border-red-200 bg-red-50 p-3 text-red-700 text-xs">
                {error}
              </div>
            )}

            {isAnonymized && (
              <div className="rounded-md border border-green-200 bg-green-50 p-3 text-green-700 text-xs">
                PII for this customer has already been erased on{" "}
                {customer.anonymizedAt ? new Date(customer.anonymizedAt).toLocaleDateString() : "-"}.
              </div>
            )}

            {privacyGdpr && (
              <div className="rounded-md border border-[var(--border)] p-4 space-y-2">
                <p className="font-medium text-[var(--foreground)]">GDPR - Right to erasure (EU)</p>
                <p className="text-xs">
                  Anonymises name, email, phone, address and notes. The customer record and all
                  business data are retained. Portal access (contacts) are removed.
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  className="text-amber-700 border-amber-300 hover:bg-amber-50"
                  onClick={handleGdprErase}
                  disabled={erasing || isAnonymized}
                >
                  {erasing && <Loader2 className="h-3 w-3 animate-spin mr-1" />}
                  {isAnonymized ? "Already erased" : "Erase personal data (GDPR)"}
                </Button>
              </div>
            )}

            {privacyCcpa && (
              <div className="rounded-md border border-[var(--border)] p-4 space-y-3">
                <p className="font-medium text-[var(--foreground)]">CCPA / CPRA - Data rights (California)</p>
                <div className="space-y-2">
                  <div>
                    <p className="text-xs font-medium text-[var(--foreground)] mb-1">Right to know - export data</p>
                    <p className="text-xs">
                      Download a JSON file containing all personal data we hold for this customer.
                    </p>
                    <Button
                      variant="outline"
                      size="sm"
                      className="mt-2"
                      onClick={handleCcpaExport}
                      disabled={exporting}
                    >
                      {exporting ? (
                        <Loader2 className="h-3 w-3 animate-spin mr-1" />
                      ) : (
                        <Download className="h-3 w-3 mr-1" />
                      )}
                      Export data (JSON)
                    </Button>
                  </div>
                  <div>
                    <p className="text-xs font-medium text-[var(--foreground)] mb-1">Right to delete - remove personal data</p>
                    <p className="text-xs">
                      Anonymises all PII. Business records are retained. Portal contacts are removed.
                    </p>
                    <Button
                      variant="outline"
                      size="sm"
                      className="mt-2 text-amber-700 border-amber-300 hover:bg-amber-50"
                      onClick={handleCcpaDelete}
                      disabled={erasing || isAnonymized}
                    >
                      {erasing && <Loader2 className="h-3 w-3 animate-spin mr-1" />}
                      {isAnonymized ? "Already deleted" : "Delete personal data (CCPA)"}
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setPrivacyOpen(false)}>
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
