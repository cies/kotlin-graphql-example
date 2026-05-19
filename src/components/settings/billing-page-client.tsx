"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { CheckCircle, Zap, Settings, Loader2 } from "lucide-react";
import { createPlatformCheckoutSession, createPlatformBillingPortalSession } from "@/lib/actions/billing";

interface Props {
  orgSlug: string;
  isPremium: boolean;
  /** Set via /admin by platform operators when Stripe billing is not used */
  premiumComplimentary: boolean;
  /** True when there is an active Stripe platform subscription to manage */
  canManageStripe: boolean;
  subscription: {
    status: string;
    currentPeriodEnd: string | null;
  } | null;
  justUpgraded: boolean;
}

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "Active",
  TRIALING: "Trialing",
  PAST_DUE: "Past due",
  CANCELED: "Cancelled",
  UNPAID: "Unpaid",
};

export function BillingPageClient({
  orgSlug,
  isPremium,
  premiumComplimentary,
  canManageStripe,
  subscription,
  justUpgraded,
}: Props) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleUpgrade() {
    setLoading(true);
    setError(null);
    const result = await createPlatformCheckoutSession(orgSlug);
    if (result.url) {
      window.location.href = result.url;
    } else {
      setError(result.error ?? "Failed to start checkout");
      setLoading(false);
    }
  }

  async function handleManage() {
    setLoading(true);
    setError(null);
    const result = await createPlatformBillingPortalSession(orgSlug);
    if (result.url) {
      window.location.href = result.url;
    } else {
      setError(result.error ?? "Failed to open billing portal");
      setLoading(false);
    }
  }

  return (
    <div className="space-y-6">
      {justUpgraded && (
        <Alert className="border-green-200 bg-green-50">
          <CheckCircle className="h-4 w-4 text-green-600" />
          <AlertDescription className="text-green-800">
            Welcome to Premium! Your subscription is now active. Invoice personalisation and other premium features are unlocked.
          </AlertDescription>
        </Alert>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {/* Current plan */}
      <Card>
        <CardHeader>
          <CardTitle>Current plan</CardTitle>
        </CardHeader>
        <CardContent>
          {isPremium ? (
            <div className="flex items-center gap-3">
              <div className="h-10 w-10 rounded-full bg-[var(--primary)]/10 flex items-center justify-center">
                <Zap className="h-5 w-5 text-[var(--primary)]" />
              </div>
              <div>
                <div className="font-semibold">Premium</div>
                {premiumComplimentary && !canManageStripe && (
                  <div className="text-sm text-[var(--muted-foreground)]">
                    Complimentary access - no Stripe subscription on file.
                  </div>
                )}
                {subscription && (canManageStripe || !premiumComplimentary) && (
                  <div className="text-sm text-[var(--muted-foreground)]">
                    Status: {STATUS_LABELS[subscription.status] ?? subscription.status}
                    {subscription.currentPeriodEnd && (
                      <> · Renews {new Date(subscription.currentPeriodEnd).toLocaleDateString("en-GB")}</>
                    )}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <div className="h-10 w-10 rounded-full bg-[#f3f4f6] flex items-center justify-center">
                <Settings className="h-5 w-5 text-[var(--muted-foreground)]" />
              </div>
              <div>
                <div className="font-semibold">Free</div>
                <div className="text-sm text-[var(--muted-foreground)]">Basic CRM features</div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Premium features */}
      <Card>
        <CardHeader>
          <CardTitle>Premium features</CardTitle>
          <CardDescription>
            Upgrade to unlock invoice personalisation and advanced branding options.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {[
            "Custom invoice accent colour",
            "Custom invoice footer text",
            "Priority support",
            "All 4 invoice templates (Classic, Modern, Minimal, Corporate)",
          ].map((f) => (
            <div key={f} className="flex items-center gap-2">
              <CheckCircle className={`h-4 w-4 ${isPremium ? "text-green-600" : "text-[var(--muted-foreground)]"}`} />
              <span className={`text-sm ${isPremium ? "" : "text-[var(--muted-foreground)]"}`}>{f}</span>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Actions */}
      {canManageStripe ? (
        <Button variant="outline" onClick={handleManage} disabled={loading}>
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Settings className="h-4 w-4" />}
          Manage subscription
        </Button>
      ) : !isPremium ? (
        <Button onClick={handleUpgrade} disabled={loading}>
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Zap className="h-4 w-4" />}
          Upgrade to Premium
        </Button>
      ) : null}
    </div>
  );
}
