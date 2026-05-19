"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { submitQuotationRequest } from "@/lib/actions/quotation-request";

export function QuotationForm() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    const form = e.currentTarget;
    const fd = new FormData(form);
    const result = await submitQuotationRequest({
      name: String(fd.get("name") ?? ""),
      email: String(fd.get("email") ?? ""),
      company: String(fd.get("company") ?? ""),
      message: String(fd.get("message") ?? ""),
    });
    setLoading(false);
    if ("error" in result && result.error) {
      setError(result.error);
      return;
    }
    setDone(true);
    form.reset();
  }

  if (done) {
    return (
      <Alert className="border-[var(--primary)]/30 bg-[var(--muted)]/40">
        <AlertDescription>
          Thanks - we received your message and will get back to you shortly.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="q-name">Name</Label>
          <Input id="q-name" name="name" required autoComplete="name" maxLength={200} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="q-email">Work email</Label>
          <Input
            id="q-email"
            name="email"
            type="email"
            required
            autoComplete="email"
            maxLength={320}
          />
        </div>
      </div>
      <div className="space-y-2">
        <Label htmlFor="q-company">Company (optional)</Label>
        <Input id="q-company" name="company" autoComplete="organization" maxLength={200} />
      </div>
      <div className="space-y-2">
        <Label htmlFor="q-message">What do you need?</Label>
        <Textarea
          id="q-message"
          name="message"
          required
          rows={5}
          maxLength={5000}
          placeholder="Briefly describe your team size, use case, and how we can help."
          className="resize-y min-h-[120px]"
        />
      </div>
      <Button type="submit" className="w-full sm:w-auto" disabled={loading}>
        {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
        Request a quotation
      </Button>
    </form>
  );
}
