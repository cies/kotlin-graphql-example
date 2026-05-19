"use client";

import { Suspense, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { signIn } from "next-auth/react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";

export default function PortalLoginPage() {
  return (
    <Suspense fallback={null}>
      <PortalLoginInner />
    </Suspense>
  );
}

function PortalLoginInner() {
  const searchParams = useSearchParams();
  const routeParams = useParams<{ orgSlug: string }>();
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [loading, setLoading] = useState(false);
  const callbackUrl = searchParams.get("callbackUrl");
  const orgSlug = routeParams.orgSlug;

  return (
    <div className="min-h-screen bg-[var(--muted)] flex items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Portal login</CardTitle>
          <CardDescription>Enter your email to receive a secure magic link.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {sent ? (
            <p className="text-sm text-green-700 bg-green-50 border border-green-200 rounded-md p-3">
              Check your inbox for the sign-in link.
            </p>
          ) : (
            <>
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                />
              </div>
              <Button
                className="w-full"
                disabled={loading || !email || !orgSlug}
                onClick={async () => {
                  setLoading(true);
                  await signIn("portal-magic-link", {
                    email,
                    callbackUrl: callbackUrl || `/portal/${orgSlug}`,
                    redirect: false,
                  });
                  setLoading(false);
                  setSent(true);
                }}
              >
                {loading ? "Sending..." : "Send magic link"}
              </Button>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
