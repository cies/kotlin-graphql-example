"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils/cn";
import {
  LayoutDashboard,
  Users,
  FolderKanban,
  FileText,
  CreditCard,
  FileSignature,
  Bell,
  Settings,
  Clock,
  Building2,
  Mail,
  ShieldCheck,
  UsersRound,
  Shield,
} from "lucide-react";

interface SidebarProps {
  orgSlug: string;
  orgName: string;
  showPlatformAdminLink?: boolean;
}

interface NavItem {
  label: string;
  href: string;
  icon: React.ElementType;
}

function getNavItems(orgSlug: string): NavItem[] {
  const base = `/${orgSlug}`;
  return [
    { label: "Dashboard", href: `${base}`, icon: LayoutDashboard },
    { label: "Customers", href: `${base}/customers`, icon: Users },
    { label: "Projects", href: `${base}/projects`, icon: FolderKanban },
    { label: "Team", href: `${base}/team`, icon: UsersRound },
    { label: "Time", href: `${base}/time`, icon: Clock },
    { label: "Invoices", href: `${base}/invoices`, icon: FileText },
    { label: "Payments", href: `${base}/payments`, icon: CreditCard },
    { label: "Contracts", href: `${base}/contracts`, icon: FileSignature },
    { label: "Reminders", href: `${base}/reminders`, icon: Bell },
    { label: "Notifications", href: `${base}/notifications`, icon: Bell },
    { label: "Templates", href: `${base}/templates`, icon: Mail },
    { label: "Audit Log", href: `${base}/audit`, icon: ShieldCheck },
    { label: "Settings", href: `${base}/settings`, icon: Settings },
  ];
}

export function Sidebar({ orgSlug, orgName, showPlatformAdminLink }: SidebarProps) {
  const pathname = usePathname();
  const navItems = getNavItems(orgSlug);

  return (
    <aside className="flex h-full min-h-0 w-[var(--sidebar-width)] flex-col border-r border-[var(--border)] bg-[var(--muted)]">
      {/* Logo / org name */}
      <div className="flex items-center gap-2 px-4 py-4 border-b border-[var(--border)]">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-[var(--primary)] text-white">
          <Building2 className="h-4 w-4" />
        </div>
        <div className="min-w-0">
          <p className="text-sm font-semibold truncate">{orgName}</p>
          <p className="text-xs text-[var(--muted-foreground)] truncate">{orgSlug}</p>
        </div>
      </div>

      {/* Nav */}
      <nav className="min-h-0 flex-1 overflow-y-auto px-2 py-3 space-y-0.5">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive =
            item.href === `/${orgSlug}`
              ? pathname === `/${orgSlug}` || pathname === `/${orgSlug}/`
              : pathname.startsWith(item.href);

          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                isActive
                  ? "bg-[var(--primary)] text-white"
                  : "text-[var(--foreground)] hover:bg-[var(--accent)]"
              )}
            >
              <Icon className="h-4 w-4 shrink-0" />
              {item.label}
            </Link>
          );
        })}
      </nav>
      {showPlatformAdminLink && (
        <div className="shrink-0 border-t border-[var(--border)] px-2 py-3">
          <Link
            href="/admin"
            className={cn(
              "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
              pathname === "/admin"
                ? "bg-[var(--primary)] text-white"
                : "text-[var(--foreground)] hover:bg-[var(--accent)]"
            )}
          >
            <Shield className="h-4 w-4 shrink-0" />
            Platform admin
          </Link>
          <p className="mt-2 px-3 text-[10px] leading-snug text-[var(--muted-foreground)]">
            Visible when your login email is listed in{" "}
            <code className="rounded bg-[var(--muted)] px-0.5 font-mono">PLATFORM_ADMIN_EMAILS</code> in the server
            environment.
          </p>
        </div>
      )}
    </aside>
  );
}
