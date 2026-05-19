"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { acceptInvite } from "@/lib/actions/team";
import { Loader2 } from "lucide-react";

interface Props {
  token: string;
  email: string;
  orgSlug: string;
  isExistingUser: boolean;
  existingName: string | null;
}

export function AcceptInviteForm({ token, email, orgSlug, isExistingUser, existingName }: Props) {
  const router = useRouter();
  const [name, setName] = useState(existingName ?? "");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError("");

    const res = await acceptInvite(token, { name, password });
    setLoading(false);

    if (res.error) {
      setError(res.error);
    } else {
      router.push(`/auth/login?callbackUrl=/${orgSlug}`);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1">
        <Label>Email</Label>
        <Input value={email} disabled className="bg-[var(--muted)]" />
      </div>

      {!isExistingUser && (
        <div className="space-y-1">
          <Label htmlFor="name">Your name</Label>
          <Input
            id="name"
            required
            placeholder="Jane Smith"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
      )}

      <div className="space-y-1">
        <Label htmlFor="password">
          {isExistingUser ? "Confirm your password" : "Create a password"}
        </Label>
        <Input
          id="password"
          type="password"
          required
          minLength={8}
          placeholder="At least 8 characters"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="new-password"
        />
      </div>

      {error && <p className="text-sm text-[var(--destructive)]">{error}</p>}

      <Button type="submit" className="w-full" disabled={loading}>
        {loading && <Loader2 className="h-4 w-4 animate-spin" />}
        Accept invitation
      </Button>
    </form>
  );
}
