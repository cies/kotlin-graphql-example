import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { createReminder, deleteReminder } from "@/lib/actions/reminders";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { ReminderChannel, ReminderTargetType } from "@prisma/client";
import { formatDateTime } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function RemindersPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const [users, reminders] = await Promise.all([
    prisma.user.findMany({
      where: {
        OR: [
          { orgMemberships: { some: { organizationId: org.id } } },
          { contactProfile: { is: { organizationId: org.id } } },
        ],
      },
      select: { id: true, name: true, email: true, userType: true },
      orderBy: [{ userType: "asc" }, { name: "asc" }],
    }),
    prisma.reminder.findMany({
      where: { organizationId: org.id },
      orderBy: [{ sent: "asc" }, { notifyAt: "asc" }],
    }),
  ]);

  const targets = await Promise.all([
    prisma.customer.findMany({ where: { organizationId: org.id }, select: { id: true, companyName: true, firstName: true, lastName: true } }),
    prisma.project.findMany({ where: { organizationId: org.id }, select: { id: true, name: true } }),
    prisma.task.findMany({ where: { organizationId: org.id }, select: { id: true, title: true } }),
    prisma.invoice.findMany({ where: { organizationId: org.id }, select: { id: true, number: true } }),
  ]);

  const [customers, projects, tasks, invoices] = targets;
  const targetOptions = [
    ...customers.map((c) => ({ id: c.id, label: c.companyName || `${c.firstName || ""} ${c.lastName || ""}`.trim() || "Customer", type: "CUSTOMER" as const })),
    ...projects.map((p) => ({ id: p.id, label: p.name, type: "PROJECT" as const })),
    ...tasks.map((t) => ({ id: t.id, label: t.title, type: "TASK" as const })),
    ...invoices.map((i) => ({ id: i.id, label: i.number, type: "INVOICE" as const })),
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Reminders</h1>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">
          Schedule reminders with email, SMS, and in-app channels.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>New reminder</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            className="grid grid-cols-1 gap-4 md:grid-cols-2"
            action={async (formData) => {
              "use server";
              const channels = formData.getAll("channels") as ReminderChannel[];
              const recipients = formData.getAll("recipientIds") as string[];
              await createReminder(orgSlug, {
                title: String(formData.get("title") || ""),
                description: String(formData.get("description") || ""),
                notifyAt: String(formData.get("notifyAt") || ""),
                targetType: String(formData.get("targetType") || "CUSTOMER") as ReminderTargetType,
                targetId: String(formData.get("targetId") || ""),
                channels,
                recipientIds: recipients,
              });
            }}
          >
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="title">Title</Label>
              <Input id="title" name="title" required />
            </div>
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="description">Description</Label>
              <Input id="description" name="description" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="notifyAt">Notify at</Label>
              <Input id="notifyAt" name="notifyAt" type="datetime-local" required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="targetType">Target type</Label>
              <Select name="targetType" defaultValue="CUSTOMER">
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CUSTOMER">Customer</SelectItem>
                  <SelectItem value="PROJECT">Project</SelectItem>
                  <SelectItem value="TASK">Task</SelectItem>
                  <SelectItem value="INVOICE">Invoice</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="targetId">Target</Label>
              <select
                id="targetId"
                name="targetId"
                className="h-10 w-full rounded-md border border-[var(--border)] bg-[var(--background)] px-3 text-sm"
                required
              >
                {targetOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    [{option.type}] {option.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-2">
              <Label>Channels</Label>
              <div className="space-y-1 text-sm">
                {["EMAIL", "SMS", "INAPP"].map((channel) => (
                  <label key={channel} className="flex items-center gap-2">
                    <input type="checkbox" name="channels" value={channel} defaultChecked={channel !== "SMS"} />
                    {channel}
                  </label>
                ))}
              </div>
            </div>

            <div className="space-y-2">
              <Label>Recipients</Label>
              <div className="max-h-36 overflow-y-auto space-y-1 rounded-md border border-[var(--border)] p-2 text-sm">
                {users.map((u) => (
                  <label key={u.id} className="flex items-center gap-2">
                    <input type="checkbox" name="recipientIds" value={u.id} defaultChecked={u.userType === "STAFF"} />
                    <span>{u.name || u.email} ({u.userType === "STAFF" ? "Staff" : "Contact"})</span>
                  </label>
                ))}
              </div>
            </div>

            <div className="md:col-span-2">
              <Button type="submit">Create reminder</Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Scheduled reminders</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {reminders.length === 0 && (
            <p className="text-sm text-[var(--muted-foreground)]">No reminders yet.</p>
          )}
          {reminders.map((reminder) => (
            <div key={reminder.id} className="rounded-md border border-[var(--border)] p-3">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="font-medium">{reminder.title}</p>
                  {reminder.description && (
                    <p className="text-sm text-[var(--muted-foreground)]">{reminder.description}</p>
                  )}
                  <p className="text-xs mt-2 text-[var(--muted-foreground)]">
                    {reminder.targetType} · {formatDateTime(reminder.notifyAt)} · {reminder.channels.join(", ")}
                  </p>
                </div>
                <form
                  action={async () => {
                    "use server";
                    await deleteReminder(orgSlug, reminder.id);
                  }}
                >
                  <Button size="sm" variant="outline">Delete</Button>
                </form>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
