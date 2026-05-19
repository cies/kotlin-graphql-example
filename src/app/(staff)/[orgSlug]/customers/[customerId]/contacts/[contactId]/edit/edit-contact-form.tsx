"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Loader2 } from "lucide-react";
import { updateContact } from "@/lib/actions/customers";

interface Props {
  orgSlug: string;
  customerId: string;
  contactId: string;
  initialData: {
    name: string;
    email: string;
    isPrimary: boolean;
    canSeeProjects: boolean;
    canSeeTasks: boolean;
    canSeeInvoices: boolean;
    canSeeContracts: boolean;
    canPayInvoices: boolean;
  };
}

type FormState = Omit<Props["initialData"], "email">;

export function EditContactForm({ orgSlug, customerId, contactId, initialData }: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<FormState>({
    name: initialData.name,
    isPrimary: initialData.isPrimary,
    canSeeProjects: initialData.canSeeProjects,
    canSeeTasks: initialData.canSeeTasks,
    canSeeInvoices: initialData.canSeeInvoices,
    canSeeContracts: initialData.canSeeContracts,
    canPayInvoices: initialData.canPayInvoices,
  });

  function handleChange(field: string, value: string | boolean) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const result = await updateContact(orgSlug, customerId, contactId, form);
      if ("error" in result && result.error) {
        setError(result.error);
      } else {
        router.push(`/${orgSlug}/customers/${customerId}`);
      }
    } catch {
      setError("Something went wrong.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Account</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Full name *</Label>
            <Input
              id="name"
              value={form.name}
              onChange={(e) => handleChange("name", e.target.value)}
              required
            />
          </div>
          <div className="space-y-2">
            <Label>Email</Label>
            <Input value={initialData.email} disabled className="opacity-60" />
            <p className="text-xs text-[var(--muted-foreground)]">
              Email address cannot be changed here.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Portal Access</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {[
            { key: "isPrimary", label: "Primary contact" },
            { key: "canSeeProjects", label: "Can view projects" },
            { key: "canSeeTasks", label: "Can view tasks" },
            { key: "canSeeInvoices", label: "Can view invoices" },
            { key: "canSeeContracts", label: "Can view contracts" },
            { key: "canPayInvoices", label: "Can pay invoices" },
          ].map(({ key, label }) => (
            <div key={key} className="flex items-center justify-between">
              <Label htmlFor={key}>{label}</Label>
              <Switch
                id={key}
                checked={form[key as keyof FormState] as boolean}
                onCheckedChange={(v) => handleChange(key, v)}
              />
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="flex gap-3">
        <Button type="submit" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin mr-2" />}
          Save changes
        </Button>
        <Button type="button" variant="outline" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
