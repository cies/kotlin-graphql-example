import NextAuth, { type NextAuthConfig } from "next-auth";
import type { JWT } from "next-auth/jwt";
import Credentials from "next-auth/providers/credentials";
import Nodemailer from "next-auth/providers/nodemailer";
import { PrismaAdapter } from "@auth/prisma-adapter";
import { normalizeAuthUrlEnv } from "@/lib/env/normalize-auth-url-env";
import { getAuthSecret } from "@/lib/env/runtime-auth-env";
import { prisma } from "@/lib/db/prisma";
import bcrypt from "bcryptjs";
import { z } from "zod";

normalizeAuthUrlEnv();

/** Loads org + role into the JWT. Only call on sign-in or explicit session.update - not on every request. */
async function hydrateJwtFromDatabase(token: JWT, userId: string): Promise<JWT> {
  const dbUser = await prisma.user.findUnique({
    where: { id: userId },
    include: {
      orgMemberships: {
        include: { organization: { select: { id: true, slug: true, name: true } } },
        take: 1,
        orderBy: { createdAt: "asc" },
      },
    },
  });

  if (!dbUser) return token;

  token.userType = dbUser.userType;
  token.email = dbUser.email;
  if (dbUser.orgMemberships[0]) {
    token.organizationId = dbUser.orgMemberships[0].organizationId;
    token.organizationSlug = dbUser.orgMemberships[0].organization.slug;
    token.role = dbUser.orgMemberships[0].role;
  } else if (dbUser.userType === "CUSTOMER_CONTACT") {
    const contact = await prisma.customerContact.findUnique({
      where: { userId: dbUser.id },
      include: { customer: { include: { organization: { select: { id: true, slug: true } } } } },
    });
    if (contact?.customer?.organization) {
      token.organizationId = contact.organizationId;
      token.organizationSlug = contact.customer.organization.slug;
      token.role = undefined;
    } else {
      token.organizationId = undefined;
      token.organizationSlug = undefined;
      token.role = undefined;
    }
  } else {
    token.organizationId = undefined;
    token.organizationSlug = undefined;
    token.role = undefined;
  }

  return token;
}

const credentialsSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

const providers: NextAuthConfig["providers"] = [];

// Magic-link provider is only enabled when EMAIL_SERVER is configured.
// Without it, Auth.js's Nodemailer provider throws at boot time, breaking
// builds and dev runs for users that don't need magic-link sign-in yet.
if (process.env.EMAIL_SERVER) {
  providers.push(
    Nodemailer({
      id: "portal-magic-link",
      server: process.env.EMAIL_SERVER,
      from: process.env.EMAIL_FROM || "no-reply@example.com",
      async sendVerificationRequest({ identifier, url, provider }) {
        const nodemailer = await import("nodemailer");
        const transporter = nodemailer.default.createTransport(provider.server);
        await transporter.sendMail({
          to: identifier,
          from: provider.from,
          subject: "Your secure sign-in link",
          html: `<p>Use this secure link to sign in:</p><p><a href="${url}">${url}</a></p>`,
          text: `Use this secure link to sign in: ${url}`,
        });
      },
    })
  );
}

providers.push(
  Credentials({
    name: "credentials",
    credentials: {
      email: { label: "Email", type: "email" },
      password: { label: "Password", type: "password" },
    },
    async authorize(credentials) {
      const parsed = credentialsSchema.safeParse(credentials);
      if (!parsed.success) return null;

      const user = await prisma.user.findUnique({
        where: { email: parsed.data.email },
      });

      if (!user || !user.passwordHash) return null;

      const passwordValid = await bcrypt.compare(
        parsed.data.password,
        user.passwordHash
      );

      if (!passwordValid) return null;

      return {
        id: user.id,
        email: user.email,
        name: user.name,
        userType: user.userType,
      };
    },
  })
);

export const { handlers, auth, signIn, signOut } = NextAuth({
  // Docker / Traefik / Dokploy: trust `Host` and `X-Forwarded-*` from the edge proxy.
  trustHost: true,
  secret: getAuthSecret(),
  adapter: PrismaAdapter(prisma),
  session: { strategy: "jwt" },
  pages: {
    signIn: "/auth/login",
    error: "/auth/error",
  },
  providers,
  events: {
    async signIn({ user, account }) {
      try {
        if (!user?.id) return;
        const dbUser = await prisma.user.findUnique({
          where: { id: user.id },
          include: {
            orgMemberships: { take: 1, orderBy: { createdAt: "asc" } },
            contactProfile: { select: { organizationId: true } },
          },
        });
        if (!dbUser) return;
        const orgId =
          dbUser.orgMemberships[0]?.organizationId ??
          dbUser.contactProfile?.organizationId ??
          null;
        if (!orgId) return;
        await prisma.auditLog.create({
          data: {
            organizationId: orgId,
            userId: dbUser.id,
            actorEmail: dbUser.email,
            actorType: dbUser.userType,
            action: "LOGIN",
            entityType: "AUTH",
            entityId: dbUser.id,
            metadata: { provider: account?.provider ?? null },
          },
        });
      } catch (err) {
        console.error("[Audit] signIn event failed:", err);
      }
    },
    async signOut(message) {
      try {
        const userId = "token" in message ? message.token?.id : undefined;
        if (!userId || typeof userId !== "string") return;
        const dbUser = await prisma.user.findUnique({
          where: { id: userId },
          include: {
            orgMemberships: { take: 1, orderBy: { createdAt: "asc" } },
            contactProfile: { select: { organizationId: true } },
          },
        });
        if (!dbUser) return;
        const orgId =
          dbUser.orgMemberships[0]?.organizationId ??
          dbUser.contactProfile?.organizationId ??
          null;
        if (!orgId) return;
        await prisma.auditLog.create({
          data: {
            organizationId: orgId,
            userId: dbUser.id,
            actorEmail: dbUser.email,
            actorType: dbUser.userType,
            action: "LOGOUT",
            entityType: "AUTH",
            entityId: dbUser.id,
          },
        });
      } catch (err) {
        console.error("[Audit] signOut event failed:", err);
      }
    },
  },
  callbacks: {
    async signIn({ user, account }) {
      if (account?.provider === "portal-magic-link") {
        const dbUser = await prisma.user.findUnique({
          where: { id: user.id },
          include: { contactProfile: true },
        });
        return dbUser?.userType === "CUSTOMER_CONTACT" && !!dbUser.contactProfile;
      }
      return true;
    },
    async jwt({ token, user, trigger }) {
      if (user) {
        token.id = user.id;
        token.userType = (user as { userType?: string }).userType;
        if (user.email) token.email = user.email;
        return hydrateJwtFromDatabase(token, user.id as string);
      }

      if (trigger === "update" && token.id) {
        return hydrateJwtFromDatabase(token, token.id as string);
      }

      return token;
    },
    async session({ session, token }) {
      if (token) {
        session.user.id = token.id as string;
        session.user.userType = token.userType as string;
        session.user.organizationId = token.organizationId as string | undefined;
        session.user.organizationSlug = token.organizationSlug as string | undefined;
        session.user.role = token.role as string | undefined;
        if (token.email) {
          session.user.email = token.email as string;
        }
      }
      return session;
    },
  },
});
