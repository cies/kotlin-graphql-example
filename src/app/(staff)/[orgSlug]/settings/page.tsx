import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { OrgSettingsForm } from "@/components/settings/org-settings-form";
import { serializeFxRates, serializeOrgSettings } from "@/lib/settings/serialize-for-client";
import { isOrgPremium } from "@/lib/billing/premium";
import { isPlatformAdminEmail } from "@/lib/platform-admin";
import { normalizeNumberFormatStyle } from "@/lib/utils/format";
import type { TimeRounding } from "@prisma/client";
import Link from "next/link";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export const metadata = { title: "Settings" };
const TIME_ROUNDING_VALUES = new Set(["NONE", "UP_30", "UP_60", "DOWN_30", "DOWN_60"]);

export default async function SettingsPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true, fxRates: true },
  });

  if (!org) redirect("/auth/login");

  const premium = await isOrgPremium(org.id);
  const showPlatformAdminLink = isPlatformAdminEmail(session.user.email);
  const rawTimeRoundingRows = await prisma.$queryRaw<Array<{ timeRounding: string | null }>>`
    SELECT "timeRounding" FROM "OrgSettings" WHERE "organizationId" = ${org.id}
  `;
  const rawNumberFormatRows = await prisma.$queryRaw<Array<{ numberFormatStyle: string | null }>>`
    SELECT "numberFormatStyle" FROM "OrgSettings" WHERE "organizationId" = ${org.id}
  `;
  const rawTimeRounding = rawTimeRoundingRows[0]?.timeRounding ?? null;
  const rawNumberFormatStyle = rawNumberFormatRows[0]?.numberFormatStyle ?? null;
  const serializedSettings = serializeOrgSettings(org.settings);
  const effectiveTimeRounding =
    rawTimeRounding && TIME_ROUNDING_VALUES.has(rawTimeRounding)
      ? rawTimeRounding
      : serializedSettings?.timeRounding ?? "NONE";
  const settingsForForm = serializedSettings
    ? {
        ...serializedSettings,
        companyName: serializedSettings.companyName?.trim() || org.name,
        timeRounding: effectiveTimeRounding as TimeRounding,
        numberFormatStyle: normalizeNumberFormatStyle(rawNumberFormatStyle ?? serializedSettings.numberFormatStyle),
      }
    : serializedSettings;

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">
          Organization settings and integrations
        </p>
      </div>
      {showPlatformAdminLink && (
        <p className="text-sm mb-4">
          <Link href="/admin" className="text-[var(--primary)] underline underline-offset-2 font-medium">
            Platform admin
          </Link>
          <span className="text-[var(--muted-foreground)]"> - grant Complimentary Premium</span>
        </p>
      )}
      <OrgSettingsForm
        orgSlug={orgSlug}
        settings={settingsForForm}
        organizationDisplayName={org.name}
        fxRates={serializeFxRates(org.fxRates)}
        isPremium={premium}
      />
    </div>
  );
}
