"use client";

import { useState } from "react";
import { StaffRole } from "@prisma/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { updateMember } from "@/lib/actions/team";

interface Member {
  id: string;
  role: StaffRole;
  hourlyRate: { toString(): string } | null;
  isRetainer: boolean;
  retainerHours: { toString(): string } | null;
  user: { name: string | null; email: string };
}

interface Props {
  orgSlug: string;
  member: Member;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const ROLES: StaffRole[] = ["OWNER", "ADMIN", "PROJECT_MANAGER", "STAFF"];

export function MemberEditDialog({ orgSlug, member, open, onOpenChange }: Props) {
  const [role, setRole] = useState<StaffRole>(member.role);
  const [hourlyRate, setHourlyRate] = useState(
    member.hourlyRate ? String(member.hourlyRate) : ""
  );
  const [isRetainer, setIsRetainer] = useState(member.isRetainer);
  const [retainerHours, setRetainerHours] = useState(
    member.retainerHours ? String(member.retainerHours) : ""
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSave() {
    setLoading(true);
    setError("");
    const res = await updateMember(orgSlug, member.id, {
      role,
      hourlyRate,
      isRetainer,
      retainerHours,
    });
    setLoading(false);
    if (res.error) {
      setError(res.error);
    } else {
      onOpenChange(false);
    }
  }

  const displayName = member.user.name || member.user.email;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Edit {displayName}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4 pt-2">
          <div className="space-y-1">
            <Label>Role</Label>
            <Select value={role} onValueChange={(v) => setRole(v as StaffRole)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ROLES.map((r) => (
                  <SelectItem key={r} value={r}>
                    {r.replace("_", " ")}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1">
            <Label>Hourly rate (internal billing)</Label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--muted-foreground)] text-sm">
                €
              </span>
              <Input
                type="number"
                min="0"
                step="0.01"
                className="pl-7"
                placeholder="0.00"
                value={hourlyRate}
                onChange={(e) => setHourlyRate(e.target.value)}
              />
            </div>
            <p className="text-xs text-[var(--muted-foreground)]">
              Used for internal cost tracking; not shown to customers.
            </p>
          </div>

          <div className="flex items-center justify-between">
            <div>
              <Label>Retainer</Label>
              <p className="text-xs text-[var(--muted-foreground)]">
                This member works on a fixed monthly retainer
              </p>
            </div>
            <Switch checked={isRetainer} onCheckedChange={setIsRetainer} />
          </div>

          {isRetainer && (
            <div className="space-y-1">
              <Label>Retainer hours / month (optional ETA)</Label>
              <Input
                type="number"
                min="0"
                step="0.5"
                placeholder="e.g. 40"
                value={retainerHours}
                onChange={(e) => setRetainerHours(e.target.value)}
              />
            </div>
          )}

          {error && <p className="text-sm text-[var(--destructive)]">{error}</p>}

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={loading}>
              {loading ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
