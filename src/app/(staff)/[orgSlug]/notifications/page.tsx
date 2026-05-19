import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { markAllNotificationsRead, markNotificationRead } from "@/lib/actions/reminders";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function NotificationsPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const notifications = await prisma.notification.findMany({
    where: {
      organizationId: org.id,
      userId: session.user.id,
    },
    orderBy: { createdAt: "desc" },
  });

  const unreadCount = notifications.filter((n) => !n.readAt).length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Notifications</h1>
          <p className="text-sm text-[var(--muted-foreground)] mt-1">
            {unreadCount} unread notification{unreadCount === 1 ? "" : "s"}
          </p>
        </div>
        {unreadCount > 0 && (
          <form
            action={async () => {
              "use server";
              await markAllNotificationsRead(orgSlug);
            }}
          >
            <Button variant="outline">Mark all read</Button>
          </form>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Inbox</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {notifications.length === 0 && (
            <p className="text-sm text-[var(--muted-foreground)]">No notifications yet.</p>
          )}
          {notifications.map((notification) => (
            <div
              key={notification.id}
              className="rounded-md border border-[var(--border)] p-3"
              style={{ backgroundColor: notification.readAt ? "transparent" : "var(--muted)" }}
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="font-medium">
                    {notification.link ? (
                      <Link
                        href={notification.link}
                        className="text-[var(--primary)] hover:underline"
                      >
                        {notification.title}
                      </Link>
                    ) : (
                      notification.title
                    )}
                  </p>
                  {notification.body && (
                    <p className="text-sm text-[var(--muted-foreground)]">{notification.body}</p>
                  )}
                  {notification.link && (
                    <p className="text-xs mt-1">
                      <Link href={notification.link} className="text-[var(--primary)] hover:underline">
                        Open
                      </Link>
                    </p>
                  )}
                  <p className="text-xs mt-2 text-[var(--muted-foreground)]">
                    {formatDateTime(notification.createdAt)}
                  </p>
                </div>
                {!notification.readAt && (
                  <form
                    action={async () => {
                      "use server";
                      await markNotificationRead(notification.id);
                    }}
                  >
                    <Button variant="ghost" size="sm">
                      Mark read
                    </Button>
                  </form>
                )}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
