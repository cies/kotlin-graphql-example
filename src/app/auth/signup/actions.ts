"use server";

import { prisma } from "@/lib/db/prisma";
import bcrypt from "bcryptjs";
import { z } from "zod";
import { slugify } from "@/lib/utils/format";
import { isPublicRegistrationEnabled } from "@/lib/env/public-registration";

const signupSchema = z.object({
  orgName: z.string().min(2, "Organization name must be at least 2 characters"),
  name: z.string().min(2, "Your name must be at least 2 characters"),
  email: z.string().email("Invalid email address"),
  password: z.string().min(8, "Password must be at least 8 characters"),
});

export type SignupInput = z.infer<typeof signupSchema>;

export async function signupAction(input: SignupInput) {
  if (!isPublicRegistrationEnabled()) {
    return { error: "Registration is not open. Contact us if you need access." };
  }

  const parsed = signupSchema.safeParse(input);
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }

  const { orgName, name, email, password } = parsed.data;

  const existingUser = await prisma.user.findUnique({ where: { email } });
  if (existingUser) {
    return { error: "An account with this email already exists." };
  }

  const baseSlug = slugify(orgName);
  let slug = baseSlug;
  let counter = 1;
  while (await prisma.organization.findUnique({ where: { slug } })) {
    slug = `${baseSlug}-${counter++}`;
  }

  const passwordHash = await bcrypt.hash(password, 12);

  const result = await prisma.$transaction(async (tx) => {
    const user = await tx.user.create({
      data: {
        email,
        name,
        passwordHash,
        userType: "STAFF",
      },
    });

    const org = await tx.organization.create({
      data: {
        name: orgName,
        slug,
        members: {
          create: {
            userId: user.id,
            role: "OWNER",
          },
        },
        settings: {
          create: {},
        },
      },
    });

    return { user, org };
  });

  return { success: true, orgSlug: result.org.slug };
}
