"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Loader2, CheckCircle, Palette } from "lucide-react";
import { updateEmailDesignForOrg } from "@/lib/actions/templates";
import type { EmailDesign } from "@prisma/client";

const DESIGNS: { value: EmailDesign; label: string; desc: string }[] = [
  { value: "MODERN_MINIMAL", label: "Modern Minimal", desc: "Clean white card, subtle accent border, sans-serif." },
  { value: "CLASSIC_PROFESSIONAL", label: "Classic Professional", desc: "Coloured header band, serif body, ruled footer." },
  { value: "BRANDED_BOLD", label: "Branded Bold", desc: "Bold accent header, strong CTA styling." },
];

interface Props {
  orgSlug: string;
  initialDesign: EmailDesign;
  initialAccentColor: string | null;
}

export function EmailDesignPanel({ orgSlug, initialDesign, initialAccentColor }: Props) {
  const [design, setDesign] = useState<EmailDesign>(initialDesign);
  const [accentColor, setAccentColor] = useState(initialAccentColor ?? "");
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isDirty = design !== initialDesign || accentColor !== (initialAccentColor ?? "");

  async function handleSave() {
    setSaving(true);
    setError(null);
    setSuccess(false);
    const res = await updateEmailDesignForOrg(orgSlug, design, accentColor);
    setSaving(false);
    if ("error" in res && res.error) {
      setError(res.error);
    } else {
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Palette className="h-4 w-4" />
          Email design
        </CardTitle>
        <CardDescription>
          Applied to every outbound email. Changes are reflected immediately in template previews.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {DESIGNS.map((d) => (
            <button
              key={d.value}
              type="button"
              onClick={() => setDesign(d.value)}
              className={`rounded-lg border-2 p-4 text-left transition-colors ${
                design === d.value
                  ? "border-[var(--primary)] bg-[var(--primary)]/5"
                  : "border-[var(--border)] hover:border-[var(--primary)]/50"
              }`}
            >
              <div className="font-semibold text-sm mb-1">{d.label}</div>
              <div className="text-xs text-[var(--muted-foreground)]">{d.desc}</div>
              {design === d.value && (
                <div className="mt-2 text-xs font-medium text-[var(--primary)]">✓ Selected</div>
              )}
            </button>
          ))}
        </div>

        <div className="space-y-2">
          <Label>
            Accent colour{" "}
            <span className="text-[var(--muted-foreground)] font-normal text-xs">
              (hex, e.g. #2563eb - used in headers and buttons)
            </span>
          </Label>
          <div className="flex items-center gap-2">
            <Input
              value={accentColor}
              onChange={(e) => setAccentColor(e.target.value)}
              placeholder="#2563eb"
              className="max-w-xs"
            />
            {accentColor && (
              <div
                className="h-8 w-8 rounded border border-[var(--border)] shrink-0"
                style={{ background: accentColor }}
              />
            )}
          </div>
        </div>

        {error && (
          <p className="text-sm text-[var(--destructive)]">{error}</p>
        )}

        <div className="flex items-center gap-3">
          <Button onClick={handleSave} disabled={saving || !isDirty} size="sm">
            {saving && <Loader2 className="h-3 w-3 animate-spin mr-1" />}
            Save design
          </Button>
          {success && (
            <span className="flex items-center gap-1 text-sm text-emerald-700">
              <CheckCircle className="h-4 w-4" />
              Saved
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
