"use client";

import * as React from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { resetTemplate, sendTestTemplate, upsertTemplate, renderTemplatePreview, updateEmailDesignForOrg } from "@/lib/actions/templates";
import { Save, RotateCcw, Send, Eye, Code as CodeIcon, Palette, Loader2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { toast } from "sonner";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import type { EmailDesign } from "@prisma/client";

const DESIGNS: { value: EmailDesign; label: string; desc: string }[] = [
  { value: "MODERN_MINIMAL", label: "Modern Minimal", desc: "Clean white card, subtle accent border, sans-serif." },
  { value: "CLASSIC_PROFESSIONAL", label: "Classic Professional", desc: "Coloured header band, serif body, ruled footer." },
  { value: "BRANDED_BOLD", label: "Branded Bold", desc: "Bold accent header, strong CTA styling." },
];

export interface TemplateEditorProps {
  orgSlug: string;
  templateKey: string;
  initialSubject: string;
  initialBody: string;
  defaultSubject: string;
  defaultBody: string;
  isCustomised: boolean;
  availableTags: string[];
  sampleVars: Record<string, string>;
  initialEmailDesign: EmailDesign;
  initialAccentColor: string | null;
}

export function TemplateEditor({
  orgSlug,
  templateKey,
  initialSubject,
  initialBody,
  defaultSubject,
  defaultBody,
  isCustomised,
  availableTags,
  sampleVars,
  initialEmailDesign,
  initialAccentColor,
}: TemplateEditorProps) {
  const [subject, setSubject] = React.useState(initialSubject);
  const [body, setBody] = React.useState(initialBody);
  const [view, setView] = React.useState<"split" | "preview">("split");
  const [saving, setSaving] = React.useState(false);
  const [testing, setTesting] = React.useState(false);
  const [resetting, setResetting] = React.useState(false);
  const [resetOpen, setResetOpen] = React.useState(false);
  const [showTestDialog, setShowTestDialog] = React.useState(false);
  const [testEmail, setTestEmail] = React.useState("");

  // Design picker state
  const [showDesignDialog, setShowDesignDialog] = React.useState(false);
  const [emailDesign, setEmailDesign] = React.useState<EmailDesign>(initialEmailDesign);
  const [accentColor, setAccentColor] = React.useState(initialAccentColor ?? "");
  const [savingDesign, setSavingDesign] = React.useState(false);

  // Server-composed preview state
  const [previewHtml, setPreviewHtml] = React.useState<string>("");
  const [previewSubject, setPreviewSubject] = React.useState<string>("");
  const [previewLoading, setPreviewLoading] = React.useState(false);
  const debounceRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewGenRef = React.useRef(0);

  const isDirty = subject !== initialSubject || body !== initialBody;

  // Fetch composed preview from server whenever subject/body/design changes
  const fetchPreview = React.useCallback(
    async (subjectVal: string, bodyVal: string) => {
      const gen = ++previewGenRef.current;
      setPreviewLoading(true);
      try {
        const result = await renderTemplatePreview(orgSlug, templateKey, subjectVal, bodyVal);
        if (gen !== previewGenRef.current) return;
        if ("error" in result) {
          setPreviewHtml(`<p style="color:red">${result.error}</p>`);
          setPreviewSubject("");
        } else {
          setPreviewHtml(result.html);
          setPreviewSubject(result.subject);
        }
      } catch {
        if (gen !== previewGenRef.current) return;
        setPreviewHtml(`<p style="color:red">Preview unavailable</p>`);
      } finally {
        if (gen === previewGenRef.current) setPreviewLoading(false);
      }
    },
    [orgSlug, templateKey]
  );

  React.useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  // Initial preview load
  React.useEffect(() => {
    fetchPreview(subject, body);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Debounced re-render on content edits
  function schedulePreview(subjectVal: string, bodyVal: string) {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => fetchPreview(subjectVal, bodyVal), 800);
  }

  function handleSubjectChange(val: string) {
    setSubject(val);
    schedulePreview(val, body);
  }

  function handleBodyChange(val: string) {
    setBody(val);
    schedulePreview(subject, val);
  }

  const insertTag = (tag: string) => {
    const updated = body + tag;
    setBody(updated);
    schedulePreview(subject, updated);
  };

  const handleSave = async () => {
    setSaving(true);
    const result = await upsertTemplate(orgSlug, { key: templateKey, subject, bodyMjml: body });
    setSaving(false);
    if ("error" in result && result.error) {
      toast.error(result.error);
    } else {
      toast.success("Template saved");
    }
  };

  const handleResetConfirmed = async () => {
    setResetOpen(false);
    setResetting(true);
    const result = await resetTemplate(orgSlug, templateKey);
    setResetting(false);
    if ("error" in result && result.error) {
      toast.error(result.error);
    } else {
      setSubject(defaultSubject);
      setBody(defaultBody);
      fetchPreview(defaultSubject, defaultBody);
      toast.success("Template reset to default");
    }
  };

  const handleSendTest = async () => {
    setTesting(true);
    const result = await sendTestTemplate(orgSlug, templateKey, testEmail);
    setTesting(false);
    if ("error" in result && result.error) {
      toast.error(result.error);
    } else {
      setShowTestDialog(false);
      toast.success(`Test email sent to ${testEmail}`);
    }
  };

  const handleSaveDesign = async () => {
    setSavingDesign(true);
    const result = await updateEmailDesignForOrg(orgSlug, emailDesign, accentColor);
    setSavingDesign(false);
    if ("error" in result && result.error) {
      toast.error(result.error);
    } else {
      setShowDesignDialog(false);
      fetchPreview(subject, body);
      toast.success("Email design saved. Preview updated.");
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          {isCustomised && !isDirty && <Badge variant="info">Customised version saved</Badge>}
          {isDirty && <Badge variant="warning">Unsaved changes</Badge>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* Design picker */}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowDesignDialog(true)}
            title="Change email design wrapper applied to all templates"
          >
            <Palette className="h-4 w-4" />
            Email design
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={() => setView(view === "split" ? "preview" : "split")}
          >
            {view === "split" ? (
              <><Eye className="h-4 w-4" />Preview only</>
            ) : (
              <><CodeIcon className="h-4 w-4" />Edit &amp; preview</>
            )}
          </Button>

          {isCustomised && (
            <Button variant="outline" size="sm" onClick={() => setResetOpen(true)} disabled={resetting}>
              <RotateCcw className="h-4 w-4" />
              {resetting ? "Resetting..." : "Reset to default"}
            </Button>
          )}

          <Button variant="outline" size="sm" onClick={() => setShowTestDialog(true)}>
            <Send className="h-4 w-4" />
            Send test
          </Button>

          <Button size="sm" onClick={handleSave} disabled={saving || !isDirty}>
            <Save className="h-4 w-4" />
            {saving ? "Saving..." : "Save changes"}
          </Button>
        </div>
      </div>

      <div className={`grid gap-4 ${view === "split" ? "lg:grid-cols-2" : "grid-cols-1"}`}>
        {view === "split" && (
          <Card>
            <CardHeader>
              <CardTitle>Editor</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="subject">Subject</Label>
                <Input
                  id="subject"
                  value={subject}
                  onChange={(e) => handleSubjectChange(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="body">Body (HTML)</Label>
                <Textarea
                  id="body"
                  value={body}
                  onChange={(e) => handleBodyChange(e.target.value)}
                  rows={18}
                  className="font-mono text-xs"
                />
              </div>

              <div>
                <p className="text-xs text-[var(--muted-foreground)] mb-2">
                  Available merge tags (click to insert):
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {availableTags.map((tag) => (
                    <button
                      key={tag}
                      type="button"
                      onClick={() => insertTag(tag)}
                      className="text-xs font-mono rounded border border-[var(--border)] bg-[var(--muted)] px-2 py-1 hover:bg-[var(--accent)]"
                    >
                      {tag}
                    </button>
                  ))}
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Preview - fully composed by server (1:1 with actual email) */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>Preview</span>
              {previewLoading && (
                <span className="flex items-center gap-1 text-xs font-normal text-[var(--muted-foreground)]">
                  <Loader2 className="h-3 w-3 animate-spin" />
                  Rendering…
                </span>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <p className="text-xs text-[var(--muted-foreground)]">
              This is the exact email your recipients will receive - including the design wrapper,
              logo, accent colour, and sample data filled in.
            </p>
            {previewSubject && (
              <div className="rounded-md border border-[var(--border)] bg-[var(--muted)] px-3 py-2">
                <p className="text-xs text-[var(--muted-foreground)]">Subject</p>
                <p className="text-sm font-medium mt-0.5">{previewSubject}</p>
              </div>
            )}
            <div className="rounded-md border border-[var(--border)] bg-white max-h-[600px] overflow-auto">
              {previewLoading && !previewHtml ? (
                <div className="flex items-center justify-center p-12 text-[var(--muted-foreground)]">
                  <Loader2 className="h-5 w-5 animate-spin mr-2" />
                  Loading preview…
                </div>
              ) : (
                <div dangerouslySetInnerHTML={{ __html: previewHtml }} />
              )}
            </div>
            <details className="text-xs">
              <summary className="cursor-pointer text-[var(--muted-foreground)]">
                Sample data used for preview
              </summary>
              <pre className="mt-2 rounded bg-[var(--muted)] p-2 font-mono text-[11px] overflow-x-auto">
                {JSON.stringify(sampleVars, null, 2)}
              </pre>
            </details>
          </CardContent>
        </Card>
      </div>

      {/* Send test dialog */}
      <Dialog open={showTestDialog} onOpenChange={setShowTestDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Send a test email</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <p className="text-sm text-[var(--muted-foreground)]">
              Sends the saved template with sample data - exactly as shown in the preview above.
              Unsaved changes are not included.
            </p>
            <div className="space-y-2">
              <Label htmlFor="testEmail">Recipient email</Label>
              <Input
                id="testEmail"
                type="email"
                placeholder="you@example.com"
                value={testEmail}
                onChange={(e) => setTestEmail(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowTestDialog(false)}>Cancel</Button>
            <Button onClick={handleSendTest} disabled={testing || !testEmail}>
              {testing ? "Sending..." : "Send test"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={resetOpen}
        title="Reset template?"
        description="This will discard your customisation and restore the built-in default. This cannot be undone."
        confirmLabel="Reset"
        onConfirm={handleResetConfirmed}
        onCancel={() => setResetOpen(false)}
      />

      {/* Email design dialog */}
      <Dialog open={showDesignDialog} onOpenChange={setShowDesignDialog}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>Email design</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 text-sm">
            <p className="text-[var(--muted-foreground)]">
              This is the org-wide email wrapper applied to every transactional email.
              Changes are reflected immediately in the preview.
            </p>
            <div className="grid grid-cols-1 gap-3">
              {DESIGNS.map((d) => (
                <button
                  key={d.value}
                  type="button"
                  onClick={() => setEmailDesign(d.value)}
                  className={`rounded-lg border-2 p-3 text-left transition-colors ${
                    emailDesign === d.value
                      ? "border-[var(--primary)] bg-[var(--primary)]/5"
                      : "border-[var(--border)] hover:border-[var(--primary)]/50"
                  }`}
                >
                  <div className="font-semibold text-sm">{d.label}</div>
                  <div className="text-xs text-[var(--muted-foreground)] mt-0.5">{d.desc}</div>
                  {emailDesign === d.value && (
                    <div className="mt-1 text-xs font-medium text-[var(--primary)]">✓ Selected</div>
                  )}
                </button>
              ))}
            </div>

            <div className="space-y-2">
              <Label>
                Accent colour{" "}
                <span className="text-[var(--muted-foreground)] font-normal text-xs">(hex, e.g. #2563eb)</span>
              </Label>
              <div className="flex items-center gap-2">
                <Input
                  value={accentColor}
                  onChange={(e) => setAccentColor(e.target.value)}
                  placeholder="#2563eb"
                  className="max-w-xs"
                />
                {accentColor && (
                  <div className="h-8 w-8 rounded border border-[var(--border)] shrink-0" style={{ background: accentColor }} />
                )}
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDesignDialog(false)}>Cancel</Button>
            <Button onClick={handleSaveDesign} disabled={savingDesign}>
              {savingDesign && <Loader2 className="h-3 w-3 animate-spin mr-1" />}
              Save &amp; update preview
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
