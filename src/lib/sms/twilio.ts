import { prisma } from "@/lib/db/prisma";

export async function sendTwilioSms(opts: {
  organizationId: string;
  to: string;
  body: string;
}): Promise<{ ok: boolean; error?: string }> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: opts.organizationId },
    select: {
      twilioSid: true,
      twilioToken: true,
      twilioFrom: true,
    },
  });

  if (!settings?.twilioSid || !settings.twilioToken || !settings.twilioFrom) {
    return { ok: false, error: "Twilio is not configured for this organization." };
  }

  const url = `https://api.twilio.com/2010-04-01/Accounts/${settings.twilioSid}/Messages.json`;
  const body = new URLSearchParams({
    To: opts.to,
    From: settings.twilioFrom,
    Body: opts.body,
  });

  const authHeader = Buffer.from(`${settings.twilioSid}:${settings.twilioToken}`).toString("base64");

  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Basic ${authHeader}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: body.toString(),
  });

  if (!response.ok) {
    const errorText = await response.text();
    return { ok: false, error: errorText };
  }

  return { ok: true };
}
