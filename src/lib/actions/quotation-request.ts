"use server";

import { headers } from "next/headers";
import { z } from "zod";
import { prisma } from "@/lib/db/prisma";
import { assertRateLimit } from "@/lib/rate-limit/redis";

const quotationSchema = z.object({
  name: z.string().trim().min(2, "Please enter your name.").max(200),
  email: z.string().trim().email("Enter a valid email address.").max(320),
  company: z
    .string()
    .trim()
    .max(200)
    .optional()
    .transform((s) => (s === "" ? undefined : s)),
  message: z.string().trim().min(10, "Tell us a bit more (at least 10 characters).").max(5000),
});

export type SubmitQuotationResult =
  | { success: true }
  | { error: string };

async function getRequestIp(): Promise<string> {
  const h = await headers();
  const xff = h.get("x-forwarded-for");
  if (xff) {
    const first = xff.split(",")[0]?.trim();
    if (first) return first;
  }
  return h.get("x-real-ip")?.trim() || "unknown";
}

export async function submitQuotationRequest(input: unknown): Promise<SubmitQuotationResult> {
  const parsed = quotationSchema.safeParse(input);
  if (!parsed.success) {
    return { error: parsed.error.issues[0]?.message ?? "Invalid input." };
  }

  const email = parsed.data.email.toLowerCase();
  const ip = await getRequestIp();

  const emailOk = await assertRateLimit(`quotation:email:${email}`, 5, 3600);
  if (!emailOk) {
    return { error: "Too many requests. Please try again later." };
  }
  const ipOk = await assertRateLimit(`quotation:ip:${ip}`, 15, 3600);
  if (!ipOk) {
    return { error: "Too many requests. Please try again later." };
  }

  await prisma.quotationRequest.create({
    data: {
      name: parsed.data.name,
      email: parsed.data.email,
      company: parsed.data.company ?? null,
      message: parsed.data.message,
    },
  });

  return { success: true };
}
