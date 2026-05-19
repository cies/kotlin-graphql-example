"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { createContract, updateContract } from "@/lib/actions/contracts";
import { ContractEditorDynamic } from "./contract-editor-dynamic";

interface CustomerOption {
  id: string;
  name: string;
}

interface ContractFormProps {
  orgSlug: string;
  customers: CustomerOption[];
  initial?: {
    id: string;
    customerId: string;
    title: string;
    bodyHtml: string;
  };
}

const DEFAULT_BODY = `<h2>Service Agreement</h2>
<p>This agreement is made between <strong>{{org.name}}</strong> and <strong>{{customer.companyName}}</strong>.</p>
<h3>Scope of Work</h3>
<ul>
  <li>Service item 1</li>
  <li>Service item 2</li>
</ul>
<h3>Payment Terms</h3>
<p>Payment is due within 30 days of invoice date.</p>
<h3>Acceptance</h3>
<p>By signing below, the parties agree to the terms of this contract.</p>
<p>Accepted by: {{customer.firstName}} {{customer.lastName}} ({{customer.email}})</p>`;

export function ContractForm({ orgSlug, customers, initial }: ContractFormProps) {
  const router = useRouter();
  const [title, setTitle] = useState(initial?.title || "");
  const [customerId, setCustomerId] = useState(initial?.customerId || customers[0]?.id || "");
  const [bodyHtml, setBodyHtml] = useState(initial?.bodyHtml || DEFAULT_BODY);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);

    const payload = { title, customerId, bodyHtml };
    const result = initial
      ? await updateContract(orgSlug, initial.id, payload)
      : await createContract(orgSlug, payload);

    setLoading(false);
    if (result.error) {
      setError(result.error);
      return;
    }
    router.push(`/${orgSlug}/contracts`);
    router.refresh();
  }

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="title">Contract title</Label>
          <Input
            id="title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            placeholder="Master Services Agreement 2026"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="customer">Customer</Label>
          <select
            id="customer"
            value={customerId}
            onChange={(e) => setCustomerId(e.target.value)}
            className="h-10 w-full rounded-md border border-[var(--border)] bg-[var(--background)] px-3 text-sm"
          >
            {customers.map((customer) => (
              <option key={customer.id} value={customer.id}>
                {customer.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="space-y-2">
        <Label>Contract body</Label>
        <p className="text-xs text-[var(--muted-foreground)]">
          Use the toolbar to format content. Click &ldquo;Insert tag&rdquo; to add merge tags that will be replaced with customer data when sent.
        </p>
        <ContractEditorDynamic value={bodyHtml} onChange={setBodyHtml} />
      </div>

      <Button type="submit" disabled={loading}>
        {loading ? "Saving…" : initial ? "Update contract" : "Create contract"}
      </Button>
    </form>
  );
}
