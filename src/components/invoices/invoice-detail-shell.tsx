"use client";

import { useRouter, useSearchParams } from "next/navigation";
import type { ReactNode } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

const TAB_VALUES = [
  "invoice",
  "tasks",
  "activity",
  "reminders",
  "notes",
  "emails",
  "views",
] as const;
export type InvoiceDetailTab = (typeof TAB_VALUES)[number];

function isTab(v: string | null | undefined): v is InvoiceDetailTab {
  return !!v && (TAB_VALUES as readonly string[]).includes(v);
}

interface Props {
  orgSlug: string;
  invoiceId: string;
  defaultTab?: string | null;
  invoicePanel: ReactNode;
  tasksPanel: ReactNode;
  activityPanel: ReactNode;
  remindersPanel: ReactNode;
  notesPanel: ReactNode;
  emailsPanel: ReactNode;
  viewsPanel: ReactNode;
}

export function InvoiceDetailShell({
  orgSlug,
  invoiceId,
  defaultTab,
  invoicePanel,
  tasksPanel,
  activityPanel,
  remindersPanel,
  notesPanel,
  emailsPanel,
  viewsPanel,
}: Props) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromUrl = searchParams.get("tab");
  const current = isTab(fromUrl) ? fromUrl : isTab(defaultTab) ? defaultTab : "invoice";

  function setTab(next: string) {
    const url = `/${orgSlug}/invoices/${invoiceId}?tab=${encodeURIComponent(next)}`;
    router.push(url, { scroll: false });
  }

  return (
    <Tabs value={current} onValueChange={setTab} className="w-full">
      <TabsList className="h-auto w-full justify-start gap-0.5 p-1 flex-wrap">
        <TabsTrigger value="invoice">Invoice</TabsTrigger>
        <TabsTrigger value="tasks">Tasks</TabsTrigger>
        <TabsTrigger value="activity">Activity</TabsTrigger>
        <TabsTrigger value="reminders">Reminders</TabsTrigger>
        <TabsTrigger value="notes">Notes</TabsTrigger>
        <TabsTrigger value="emails">Emails</TabsTrigger>
        <TabsTrigger value="views">Views</TabsTrigger>
      </TabsList>
      <TabsContent value="invoice" className="mt-4">
        {invoicePanel}
      </TabsContent>
      <TabsContent value="tasks" className="mt-4">
        {tasksPanel}
      </TabsContent>
      <TabsContent value="activity" className="mt-4">
        {activityPanel}
      </TabsContent>
      <TabsContent value="reminders" className="mt-4">
        {remindersPanel}
      </TabsContent>
      <TabsContent value="notes" className="mt-4">
        {notesPanel}
      </TabsContent>
      <TabsContent value="emails" className="mt-4">
        {emailsPanel}
      </TabsContent>
      <TabsContent value="views" className="mt-4">
        {viewsPanel}
      </TabsContent>
    </Tabs>
  );
}
