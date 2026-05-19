"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { createReminder, deleteReminder } from "@/lib/actions/reminders";
import { Bell, Trash2, Loader2 } from "lucide-react";
import { formatDateTime } from "@/lib/utils/format";
import type { ReminderChannel } from "@prisma/client";

interface Member {
  id: string;
  name: string | null;
  email: string | null;
}

interface ReminderRow {
  id: string;
  title: string;
  description: string | null;
  notifyAt: Date | string;
  channels: ReminderChannel[];
  sent: boolean;
}

interface Props {
  orgSlug: string;
  invoiceId: string;
  reminders: ReminderRow[];
  members: Member[];
}

export function InvoiceRemindersPanel({ orgSlug, invoiceId, reminders, members }: Props) {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [notifyAt, setNotifyAt] = useState("");
  const [channelEmail, setChannelEmail] = useState(true);
  const [channelInApp, setChannelInApp] = useState(false);
  const [selectedRecipients, setSelectedRecipients] = useState<Record<string, boolean>>(() => {
    const m: Record<string, boolean> = {};
    for (const mem of members) m[mem.id] = true;
    return m;
  });
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  function toggleRecipient(id: string) {
    setSelectedRecipients((s) => ({ ...s, [id]: !s[id] }));
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const channels: ReminderChannel[] = [];
    if (channelEmail) channels.push("EMAIL");
    if (channelInApp) channels.push("INAPP");
    if (channels.length === 0) return;

    const recipientIds = Object.entries(selectedRecipients)
      .filter(([, v]) => v)
      .map(([k]) => k);
    if (recipientIds.length === 0) return;

    if (!notifyAt.trim()) return;

    setSaving(true);
    const res = await createReminder(orgSlug, {
      targetType: "INVOICE",
      targetId: invoiceId,
      title: title.trim() || "Invoice reminder",
      description: description.trim() || undefined,
      notifyAt: new Date(notifyAt).toISOString(),
      channels,
      recipientIds,
    });
    setSaving(false);
    if (!("error" in res && res.error)) {
      setTitle("");
      setDescription("");
      setNotifyAt("");
      router.refresh();
    }
  }

  async function handleDelete(reminderId: string) {
    setDeletingId(reminderId);
    await deleteReminder(orgSlug, reminderId);
    setDeletingId(null);
    router.refresh();
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Bell className="h-4 w-4" />
            New reminder
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleCreate} className="space-y-4 max-w-lg">
            <div className="space-y-2">
              <Label>Title</Label>
              <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Follow up on payment" />
            </div>
            <div className="space-y-2">
              <Label>Description (optional)</Label>
              <Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
            </div>
            <div className="space-y-2">
              <Label>Notify at</Label>
              <Input
                type="datetime-local"
                value={notifyAt}
                onChange={(e) => setNotifyAt(e.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label>Channels</Label>
              <div className="flex gap-4 text-sm">
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={channelEmail} onChange={(e) => setChannelEmail(e.target.checked)} />
                  Email
                </label>
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={channelInApp} onChange={(e) => setChannelInApp(e.target.checked)} />
                  In-app
                </label>
              </div>
            </div>
            <div className="space-y-2">
              <Label>Notify team members</Label>
              <div className="border rounded-md p-2 space-y-1 max-h-40 overflow-y-auto text-sm">
                {members.map((m) => (
                  <label key={m.id} className="flex items-center gap-2 py-0.5">
                    <input
                      type="checkbox"
                      checked={!!selectedRecipients[m.id]}
                      onChange={() => toggleRecipient(m.id)}
                    />
                    <span>{m.name || m.email || m.id}</span>
                  </label>
                ))}
                {members.length === 0 && (
                  <p className="text-[var(--muted-foreground)]">No staff members in this organization.</p>
                )}
              </div>
            </div>
            <Button type="submit" disabled={saving || members.length === 0}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : "Create reminder"}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Scheduled for this invoice</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {reminders.length === 0 ? (
            <p className="text-sm text-[var(--muted-foreground)]">No reminders linked to this invoice.</p>
          ) : (
            reminders.map((r) => (
              <div
                key={r.id}
                className="flex items-start justify-between gap-2 rounded-md border border-[var(--border)] p-3 text-sm"
              >
                <div>
                  <p className="font-medium">{r.title}</p>
                  {r.description && (
                    <p className="text-[var(--muted-foreground)] mt-0.5">{r.description}</p>
                  )}
                  <p className="text-xs text-[var(--muted-foreground)] mt-1">
                    {formatDateTime(r.notifyAt)} · {r.channels.join(", ")}
                    {r.sent ? " · Sent" : ""}
                  </p>
                </div>
                {!r.sent && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    onClick={() => handleDelete(r.id)}
                    disabled={deletingId === r.id}
                  >
                    {deletingId === r.id ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <Trash2 className="h-4 w-4" />
                    )}
                  </Button>
                )}
              </div>
            ))
          )}
        </CardContent>
      </Card>
    </div>
  );
}
