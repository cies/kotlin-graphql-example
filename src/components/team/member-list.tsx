"use client";

import { useState } from "react";
import { StaffRole } from "@prisma/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { MemberEditDialog } from "./member-edit-dialog";
import { removeMember, revokeInvite } from "@/lib/actions/team";
import { Edit2, Trash2, UserX, Clock, RefreshCw } from "lucide-react";
import { formatDate } from "@/lib/utils/format";
import { toast } from "sonner";

interface Member {
  id: string;
  role: StaffRole;
  hourlyRate: { toString(): string } | null;
  isRetainer: boolean;
  retainerHours: { toString(): string } | null;
  createdAt: Date;
  user: { id: string; name: string | null; email: string; image: string | null };
}

interface Invite {
  id: string;
  email: string;
  role: StaffRole;
  expiresAt: Date;
  createdAt: Date;
}

interface Props {
  orgSlug: string;
  members: Member[];
  invites: Invite[];
  currentUserId: string;
}

const ROLE_COLORS: Record<StaffRole, "default" | "info" | "warning" | "secondary" | "outline"> = {
  OWNER: "default",
  ADMIN: "info",
  PROJECT_MANAGER: "warning",
  STAFF: "secondary",
};

function MemberAvatar({ name, email, image }: { name: string | null; email: string; image: string | null }) {
  const initials = (name || email).slice(0, 2).toUpperCase();
  if (image) {
    return (
      <img
        src={image}
        alt={name || email}
        className="h-8 w-8 rounded-full object-cover"
      />
    );
  }
  return (
    <div className="h-8 w-8 rounded-full bg-[var(--primary)] text-white flex items-center justify-center text-xs font-semibold">
      {initials}
    </div>
  );
}

type ConfirmState = { description: string; onConfirm: () => Promise<void> } | null;

export function MemberList({ orgSlug, members, invites, currentUserId }: Props) {
  const [editingMember, setEditingMember] = useState<Member | null>(null);
  const [confirmState, setConfirmState] = useState<ConfirmState>(null);

  function openConfirm(description: string, onConfirm: () => Promise<void>) {
    setConfirmState({ description, onConfirm });
  }

  async function handleConfirm() {
    if (!confirmState) return;
    const action = confirmState.onConfirm;
    setConfirmState(null);
    try {
      await action();
    } catch {
      toast.error("Action failed. Please try again.");
    }
  }

  async function handleRemove(memberId: string, name: string) {
    openConfirm(`Remove ${name} from this organization?`, async () => {
      await removeMember(orgSlug, memberId);
      toast.success(`${name} has been removed`);
    });
  }

  async function handleRevokeInvite(inviteId: string, email: string) {
    openConfirm(`Revoke invite for ${email}?`, async () => {
      await revokeInvite(orgSlug, inviteId);
      toast.success(`Invite for ${email} revoked`);
    });
  }

  return (
    <div className="space-y-6">
      {/* Active Members */}
      <div>
        <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wider mb-3">
          Members ({members.length})
        </h3>
        <div className="divide-y divide-[var(--border)] rounded-lg border border-[var(--border)]">
          {members.map((member) => {
            const displayName = member.user.name || member.user.email;
            const isSelf = member.user.id === currentUserId;

            return (
              <div key={member.id} className="flex items-center gap-3 p-4">
                <MemberAvatar
                  name={member.user.name}
                  email={member.user.email}
                  image={member.user.image}
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium text-sm truncate">{displayName}</span>
                    {isSelf && (
                      <span className="text-xs text-[var(--muted-foreground)]">(you)</span>
                    )}
                    <Badge variant={ROLE_COLORS[member.role]} className="text-xs">
                      {member.role.replace("_", " ")}
                    </Badge>
                    {member.isRetainer && (
                      <Badge variant="outline" className="text-xs gap-1">
                        <RefreshCw className="h-2.5 w-2.5" />
                        Retainer
                        {member.retainerHours
                          ? ` · ${member.retainerHours.toString()}h/mo`
                          : ""}
                      </Badge>
                    )}
                  </div>
                  <div className="flex items-center gap-3 mt-0.5 text-xs text-[var(--muted-foreground)]">
                    <span>{member.user.email}</span>
                    {member.hourlyRate && (
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        €{member.hourlyRate.toString()}/h internal
                      </span>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8"
                    onClick={() => setEditingMember(member)}
                    title="Edit member"
                  >
                    <Edit2 className="h-3.5 w-3.5" />
                  </Button>
                  {!isSelf && (
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
                      onClick={() => handleRemove(member.id, displayName)}
                      title="Remove member"
                    >
                      <UserX className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Pending Invites */}
      {invites.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-[var(--muted-foreground)] uppercase tracking-wider mb-3">
            Pending Invites ({invites.length})
          </h3>
          <div className="divide-y divide-[var(--border)] rounded-lg border border-[var(--border)]">
            {invites.map((invite) => (
              <div key={invite.id} className="flex items-center gap-3 p-4">
                <div className="h-8 w-8 rounded-full bg-[var(--muted)] flex items-center justify-center text-[var(--muted-foreground)]">
                  <span className="text-xs font-semibold">?</span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-sm">{invite.email}</span>
                    <Badge variant={ROLE_COLORS[invite.role]} className="text-xs">
                      {invite.role.replace("_", " ")}
                    </Badge>
                    <Badge variant="outline" className="text-xs text-amber-600 border-amber-300">
                      Pending
                    </Badge>
                  </div>
                  <p className="text-xs text-[var(--muted-foreground)] mt-0.5">
                    Expires {formatDate(invite.expiresAt)}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
                  onClick={() => handleRevokeInvite(invite.id, invite.email)}
                  title="Revoke invite"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}

      {editingMember && (
        <MemberEditDialog
          orgSlug={orgSlug}
          member={editingMember}
          open={!!editingMember}
          onOpenChange={(o) => !o && setEditingMember(null)}
        />
      )}

      <ConfirmDialog
        open={!!confirmState}
        description={confirmState?.description ?? ""}
        onConfirm={handleConfirm}
        onCancel={() => setConfirmState(null)}
      />
    </div>
  );
}
