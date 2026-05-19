"use client";

import { useState } from "react";
import { StaffRole } from "@prisma/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { inviteMember } from "@/lib/actions/team";
import { useRouter } from "next/navigation";

interface Props {
  orgSlug: string;
}

const ROLES: { value: StaffRole; label: string }[] = [
  { value: "STAFF", label: "Staff" },
  { value: "PROJECT_MANAGER", label: "Project Manager" },
  { value: "ADMIN", label: "Admin" },
  { value: "OWNER", label: "Owner" },
];

export function InviteForm({ orgSlug }: Props) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<StaffRole>("STAFF");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError("");
    setSuccess(false);

    const res = await inviteMember(orgSlug, { email, role });
    setLoading(false);

    if (res.error) {
      setError(res.error);
    } else {
      setSuccess(true);
      setEmail("");
      setTimeout(() => router.push(`/${orgSlug}/team`), 1500);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4 max-w-md">
      <div className="space-y-1">
        <Label htmlFor="email">Email address</Label>
        <Input
          id="email"
          type="email"
          required
          placeholder="colleague@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
      </div>

      <div className="space-y-1">
        <Label htmlFor="role">Role</Label>
        <Select value={role} onValueChange={(v) => setRole(v as StaffRole)}>
          <SelectTrigger id="role">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ROLES.map((r) => (
              <SelectItem key={r.value} value={r.value}>
                {r.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-xs text-[var(--muted-foreground)]">
          You can change the role later from the team page.
        </p>
      </div>

      {error && <p className="text-sm text-[var(--destructive)]">{error}</p>}
      {success && (
        <p className="text-sm text-green-600">
          Invite sent! Redirecting…
        </p>
      )}

      <Button type="submit" disabled={loading}>
        {loading ? "Sending invite…" : "Send invite"}
      </Button>
    </form>
  );
}
