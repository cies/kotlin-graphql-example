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
import { createContact } from "@/lib/actions/customers";

interface Props {
  orgSlug: string;
  customerId: string;
}

export function NewContactForm({ orgSlug, customerId }: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState({
    name: "",
    email: "",
    inviteToPortal: true,
    password: "",
    isPrimary: false,
    canSeeProjects: true,
    canSeeTasks: false,
    canSeeInvoices: true,
    canSeeContracts: false,
    canPayInvoices: true,
  });

  function handleChange(field: string, value: string | boolean) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const result = await createContact(orgSlug, customerId, form);
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
          <CardTitle>Contact</CardTitle>
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
            <Label htmlFor="email">Email *</Label>
            <Input
              id="email"
              type="email"
              value={form.email}
              onChange={(e) => handleChange("email", e.target.value)}
              required
            />
          </div>

          <div className="flex items-center justify-between gap-3 rounded-lg border border-[var(--input)] px-3 py-2">
            <div>
              <Label htmlFor="inviteToPortal" className="cursor-pointer leading-snug">
                Customer portal login
              </Label>
              <p className="text-xs text-[var(--muted-foreground)] mt-0.5 max-w-[32rem]">
                Turn off if this person should only appear as an email recipient on invoices — they
                cannot sign in with a password until you set one later. Magic-link sign-in can still be
                used if your deployment has email verification configured globally.
              </p>
            </div>
            <Switch
              id="inviteToPortal"
              checked={form.inviteToPortal}
              onCheckedChange={(v) => handleChange("inviteToPortal", v)}
            />
          </div>

          {form.inviteToPortal && (
            <div className="space-y-2">
              <Label htmlFor="password">Initial password *</Label>
              <Input
                id="password"
                type="password"
                autoComplete="new-password"
                value={form.password}
                onChange={(e) => handleChange("password", e.target.value)}
                placeholder="At least 8 characters"
              />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Portal access</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {!form.inviteToPortal && (
            <p className="text-xs text-[var(--muted-foreground)]">
              These switches apply once the contact can enter the portal (after a password exists or via
              magic link).
            </p>
          )}
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
                checked={form[key as keyof typeof form] as boolean}
                onCheckedChange={(v) => handleChange(key, v)}
              />
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="flex gap-3">
        <Button type="submit" disabled={loading}>
          {loading && <Loader2 className="h-4 w-4 animate-spin mr-2" />}
          Create contact
        </Button>
        <Button type="button" variant="outline" onClick={() => router.back()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
