"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { CustomerType, Currency, Prisma, CustomerNotificationPurpose } from "@prisma/client";
import bcrypt from "bcryptjs";
import { logAudit } from "@/lib/audit/log";

const addressSchema = z.object({
  line1: z.string().optional(),
  line2: z.string().optional(),
  city: z.string().optional(),
  state: z.string().optional(),
  postalCode: z.string().optional(),
  country: z.string().optional(),
}).optional();

const notificationEmailRowSchema = z.object({
  purpose: z.nativeEnum(CustomerNotificationPurpose),
  email: z.string().email(),
});

const customerSchema = z.object({
  type: z.nativeEnum(CustomerType).default("B2B"),
  companyName: z.string().optional(),
  firstName: z.string().optional(),
  lastName: z.string().optional(),
  email: z.string().email().optional().or(z.literal("")),
  phone: z.string().optional(),
  vat: z.string().optional(),
  preferredCurrency: z.nativeEnum(Currency).default("EUR"),
  billingAddress: addressSchema,
  shippingAddress: addressSchema,
  notes: z.string().optional(),
  hideEmailOnInvoice: z.boolean().default(false),
  hidePhoneOnInvoice: z.boolean().default(false),
  notificationEmails: z.array(notificationEmailRowSchema).optional(),
});

export type CustomerInput = z.infer<typeof customerSchema>;

async function getOrgId(orgSlug: string): Promise<string | null> {
  const ctx = await getOrgContext(orgSlug);
  return ctx?.orgId ?? null;
}

async function getOrgContext(
  orgSlug: string
): Promise<{ orgId: string; role: string } | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });

  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
    select: { role: true },
  });

  return member ? { orgId: org.id, role: member.role } : null;
}

function notificationEmailRowsForDb(
  orgId: string,
  customerId: string,
  rows: Array<{ purpose: CustomerNotificationPurpose; email: string }> | undefined
): Prisma.CustomerNotificationEmailCreateManyInput[] {
  if (!rows?.length) return [];
  const seen = new Set<string>();
  const out: Prisma.CustomerNotificationEmailCreateManyInput[] = [];
  for (const r of rows) {
    const e = r.email.trim().toLowerCase();
    if (!e) continue;
    const dedupe = `${r.purpose}:${e}`;
    if (seen.has(dedupe)) continue;
    seen.add(dedupe);
    out.push({
      organizationId: orgId,
      customerId,
      purpose: r.purpose,
      email: e,
    });
  }
  return out;
}

export async function createCustomer(orgSlug: string, input: CustomerInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = customerSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { notificationEmails, email, ...restCustomer } = parsed.data;

  const customer = await prisma.$transaction(async (tx) => {
    const c = await tx.customer.create({
      data: {
        ...restCustomer,
        organizationId: orgId,
        email: email || null,
      },
    });
    const ne = notificationEmailRowsForDb(orgId, c.id, notificationEmails);
    if (ne.length) {
      await tx.customerNotificationEmail.createMany({ data: ne });
    }
    return c;
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "CUSTOMER",
    entityId: customer.id,
    metadata: { type: customer.type, name: customer.companyName ?? customer.email },
  });

  revalidatePath(`/${orgSlug}/customers`);
  return { success: true, customerId: customer.id };
}

export async function updateCustomer(
  orgSlug: string,
  customerId: string,
  input: CustomerInput
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = customerSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { notificationEmails, email, ...restCustomer } = parsed.data;

  await prisma.$transaction(async (tx) => {
    await tx.customer.update({
      where: { id: customerId, organizationId: orgId },
      data: {
        ...restCustomer,
        email: email || null,
      },
    });
    await tx.customerNotificationEmail.deleteMany({
      where: { customerId, organizationId: orgId },
    });
    const ne = notificationEmailRowsForDb(orgId, customerId, notificationEmails);
    if (ne.length) {
      await tx.customerNotificationEmail.createMany({ data: ne });
    }
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CUSTOMER",
    entityId: customerId,
  });

  revalidatePath(`/${orgSlug}/customers`);
  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

export async function getCustomerRelatedCounts(orgSlug: string, customerId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return null;

  const [invoices, projects, contracts] = await Promise.all([
    prisma.invoice.count({ where: { customerId, organizationId: orgId } }),
    prisma.project.count({ where: { customerId, organizationId: orgId } }),
    prisma.contract.count({ where: { customerId, organizationId: orgId } }),
  ]);

  return { invoices, projects, contracts, total: invoices + projects + contracts };
}

export async function deleteCustomer(orgSlug: string, customerId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const [invoices, projects, contracts] = await Promise.all([
    prisma.invoice.count({ where: { customerId, organizationId: orgId } }),
    prisma.project.count({ where: { customerId, organizationId: orgId } }),
    prisma.contract.count({ where: { customerId, organizationId: orgId } }),
  ]);

  const total = invoices + projects + contracts;
  if (total > 0) {
    const parts: string[] = [];
    if (invoices > 0) parts.push(`${invoices} invoice${invoices !== 1 ? "s" : ""}`);
    if (projects > 0) parts.push(`${projects} project${projects !== 1 ? "s" : ""}`);
    if (contracts > 0) parts.push(`${contracts} contract${contracts !== 1 ? "s" : ""}`);
    return {
      error: `Cannot delete this customer because they have ${parts.join(", ")} attached. Remove those records first, or use the privacy erasure option.`,
      relatedCounts: { invoices, projects, contracts },
    };
  }

  await prisma.customer.delete({ where: { id: customerId, organizationId: orgId } });

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "CUSTOMER",
    entityId: customerId,
  });

  revalidatePath(`/${orgSlug}/customers`);
  return { success: true };
}

/**
 * GDPR (EU) erasure: anonymize PII fields but keep the customer record so
 * invoices, projects and contracts remain legally intact.
 */
export async function gdprEraseCustomer(orgSlug: string, customerId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const org = await prisma.organization.findUnique({
    where: { id: orgId },
    select: { settings: { select: { privacyGdpr: true } } },
  });
  if (!org?.settings?.privacyGdpr) {
    return { error: "GDPR erasure is not enabled for this organisation." };
  }

  const now = new Date();
  await prisma.$transaction(async (tx) => {
    await tx.customerNotificationEmail.deleteMany({
      where: { customerId, organizationId: orgId },
    });
    await tx.customer.update({
      where: { id: customerId, organizationId: orgId },
      data: {
        companyName: null,
        firstName: null,
        lastName: null,
        email: null,
        phone: null,
        vat: null,
        notes: null,
        billingAddress: Prisma.DbNull,
        shippingAddress: Prisma.DbNull,
        hideEmailOnInvoice: false,
        hidePhoneOnInvoice: false,
        anonymizedAt: now,
        privacyRequestedAt: now,
      },
    });
    // Anonymise linked portal contacts (delete their User accounts → cascade deletes contact)
    const contacts = await tx.customerContact.findMany({
      where: { customerId, organizationId: orgId },
      select: { id: true, userId: true },
    });
    for (const c of contacts) {
      await tx.customerContact.delete({ where: { id: c.id } });
      await tx.user.delete({ where: { id: c.userId } });
    }
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CUSTOMER",
    entityId: customerId,
    metadata: { gdprErasure: true, erasedAt: now.toISOString() },
  });

  revalidatePath(`/${orgSlug}/customers`);
  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

/**
 * CCPA (California): delete customer personal data and the customer record
 * itself. Business records (invoices, projects) are retained but de-linked
 * from the customer's identity (customer FK kept, PII removed).
 */
export async function ccpaDeleteCustomer(orgSlug: string, customerId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const org = await prisma.organization.findUnique({
    where: { id: orgId },
    select: { settings: { select: { privacyCcpa: true } } },
  });
  if (!org?.settings?.privacyCcpa) {
    return { error: "CCPA deletion is not enabled for this organisation." };
  }

  const now = new Date();
  await prisma.$transaction(async (tx) => {
    // Scrub all PII (same as GDPR erasure – keep record for business linkage)
    await tx.customerNotificationEmail.deleteMany({
      where: { customerId, organizationId: orgId },
    });
    await tx.customer.update({
      where: { id: customerId, organizationId: orgId },
      data: {
        companyName: null,
        firstName: null,
        lastName: null,
        email: null,
        phone: null,
        vat: null,
        notes: null,
        billingAddress: Prisma.DbNull,
        shippingAddress: Prisma.DbNull,
        hideEmailOnInvoice: false,
        hidePhoneOnInvoice: false,
        anonymizedAt: now,
        privacyRequestedAt: now,
      },
    });
    // Delete portal contacts
    const contacts = await tx.customerContact.findMany({
      where: { customerId, organizationId: orgId },
      select: { id: true, userId: true },
    });
    for (const c of contacts) {
      await tx.customerContact.delete({ where: { id: c.id } });
      await tx.user.delete({ where: { id: c.userId } });
    }
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CUSTOMER",
    entityId: customerId,
    metadata: { ccpaDeletion: true, deletedAt: now.toISOString() },
  });

  revalidatePath(`/${orgSlug}/customers`);
  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

/**
 * CCPA data export: return all personal data we hold for this customer.
 * Returns a JSON-serialisable object – caller can offer it as a download.
 */
export async function ccpaExportCustomerData(orgSlug: string, customerId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const org = await prisma.organization.findUnique({
    where: { id: orgId },
    select: { settings: { select: { privacyCcpa: true } } },
  });
  if (!org?.settings?.privacyCcpa) {
    return { error: "CCPA is not enabled for this organisation." };
  }

  const customer = await prisma.customer.findUnique({
    where: { id: customerId, organizationId: orgId },
    include: {
      notificationEmails: { select: { purpose: true, email: true } },
      contacts: { include: { user: { select: { name: true, email: true, createdAt: true } } } },
      invoices: {
        select: {
          number: true,
          status: true,
          total: true,
          currency: true,
          issuedAt: true,
          dueDate: true,
        },
      },
      projects: { select: { name: true, status: true, createdAt: true } },
    },
  });

  if (!customer) return { error: "Customer not found" };

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CUSTOMER",
    entityId: customerId,
    metadata: { ccpaExport: true },
  });

  return {
    success: true,
    data: {
      exportedAt: new Date().toISOString(),
      customer: {
        id: customer.id,
        type: customer.type,
        companyName: customer.companyName,
        firstName: customer.firstName,
        lastName: customer.lastName,
        email: customer.email,
        phone: customer.phone,
        vat: customer.vat,
        billingAddress: customer.billingAddress,
        shippingAddress: customer.shippingAddress,
        notes: customer.notes,
        createdAt: customer.createdAt.toISOString(),
      },
      notificationEmails: customer.notificationEmails.map((n) => ({
        purpose: n.purpose,
        email: n.email,
      })),
      contacts: customer.contacts.map((c) => ({
        name: c.user.name,
        email: c.user.email,
        isPrimary: c.isPrimary,
        since: c.user.createdAt.toISOString(),
      })),
      invoices: customer.invoices.map((i) => ({
        number: i.number,
        status: i.status,
        total: i.total.toString(),
        currency: i.currency,
        issuedAt: i.issuedAt.toISOString(),
        dueDate: i.dueDate?.toISOString(),
      })),
      projects: customer.projects.map((p) => ({
        name: p.name,
        status: p.status,
        since: p.createdAt.toISOString(),
      })),
    },
  };
}

export async function createCustomerNote(
  orgSlug: string,
  customerId: string,
  content: string
) {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  await prisma.customerNote.create({
    data: {
      organizationId: orgId,
      customerId,
      authorId: session.user.id,
      content,
    },
  });

  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

export async function deleteCustomerNote(
  orgSlug: string,
  customerId: string,
  noteId: string
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  await prisma.customerNote.delete({ where: { id: noteId, organizationId: orgId } });

  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

// ─── Customer Contacts ───────────────────────────────────────────────────────

const contactSchema = z
  .object({
    name: z.string().min(1),
    email: z.string().email(),
    /** When true, a password is required (credentials portal login). When false, email-only recipient (`passwordHash` null until set later). */
    inviteToPortal: z.boolean().default(true),
    password: z.string().optional(),
    isPrimary: z.boolean().default(false),
    canSeeProjects: z.boolean().default(true),
    canSeeTasks: z.boolean().default(false),
    canSeeInvoices: z.boolean().default(true),
    canSeeContracts: z.boolean().default(false),
    canPayInvoices: z.boolean().default(true),
  })
  .superRefine((data, ctx) => {
    const pw = (data.password ?? "").trim();
    if (data.inviteToPortal) {
      if (pw.length < 8) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Password must be at least 8 characters when portal login is enabled.",
          path: ["password"],
        });
      }
    } else if (pw.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "Leave the password blank for email-only contacts.",
        path: ["password"],
      });
    }
  });

export type ContactInput = z.infer<typeof contactSchema>;

export async function createContact(
  orgSlug: string,
  customerId: string,
  input: ContactInput
) {
  const ctx = await getOrgContext(orgSlug);
  if (!ctx) return { error: "Unauthorized" };
  const { orgId, role } = ctx;

  const parsed = contactSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  // B2C customers can only have additional contacts if added by an OWNER or ADMIN
  const customer = await prisma.customer.findUnique({
    where: { id: customerId, organizationId: orgId },
    select: { type: true, _count: { select: { contacts: true } } },
  });
  if (!customer) return { error: "Customer not found" };
  if (
    customer.type === "B2C" &&
    customer._count.contacts >= 1 &&
    role !== "OWNER" &&
    role !== "ADMIN"
  ) {
    return { error: "B2C customers can only have one contact. Ask an admin to add more." };
  }

  const existing = await prisma.user.findUnique({ where: { email: parsed.data.email } });
  if (existing) return { error: "A user with this email already exists." };

  const passwordHash =
    parsed.data.inviteToPortal && (parsed.data.password ?? "").trim().length >= 8
      ? await bcrypt.hash(parsed.data.password!.trim(), 12)
      : null;

  const result = await prisma.$transaction(async (tx) => {
    const user = await tx.user.create({
      data: {
        email: parsed.data.email,
        name: parsed.data.name,
        passwordHash,
        userType: "CUSTOMER_CONTACT",
      },
    });

    const contact = await tx.customerContact.create({
      data: {
        organizationId: orgId,
        customerId,
        userId: user.id,
        isPrimary: parsed.data.isPrimary,
        canSeeProjects: parsed.data.canSeeProjects,
        canSeeTasks: parsed.data.canSeeTasks,
        canSeeInvoices: parsed.data.canSeeInvoices,
        canSeeContracts: parsed.data.canSeeContracts,
        canPayInvoices: parsed.data.canPayInvoices,
      },
    });

    return { contactId: contact.id, userId: user.id };
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "CUSTOMER_CONTACT",
    entityId: result.contactId,
    metadata: {
      customerId,
      email: parsed.data.email,
      isPrimary: parsed.data.isPrimary,
      inviteToPortal: parsed.data.inviteToPortal,
    },
  });

  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

export async function updateContact(
  orgSlug: string,
  customerId: string,
  contactId: string,
  input: Partial<Omit<ContactInput, "password" | "email" | "inviteToPortal">>
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const existing = await prisma.customerContact.findUnique({
    where: { id: contactId },
    select: { organizationId: true, customerId: true, userId: true },
  });
  if (!existing || existing.organizationId !== orgId || existing.customerId !== customerId) {
    return { error: "Contact not found" };
  }

  const { name, ...contactFields } = input;

  await prisma.$transaction(async (tx) => {
    if (name !== undefined) {
      await tx.user.update({ where: { id: existing.userId }, data: { name } });
    }
    const hasContactFields = Object.keys(contactFields).length > 0;
    if (hasContactFields) {
      await tx.customerContact.update({ where: { id: contactId }, data: contactFields });
    }
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CUSTOMER_CONTACT",
    entityId: contactId,
    metadata: { customerId },
  });

  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}

export async function deleteContact(
  orgSlug: string,
  customerId: string,
  contactId: string
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const contact = await prisma.customerContact.findUnique({
    where: { id: contactId },
    select: { userId: true, organizationId: true, customerId: true },
  });

  if (!contact || contact.organizationId !== orgId || contact.customerId !== customerId) {
    return { error: "Contact not found" };
  }

  await prisma.$transaction([
    prisma.customerContact.delete({ where: { id: contactId } }),
    prisma.user.delete({ where: { id: contact.userId } }),
  ]);

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "CUSTOMER_CONTACT",
    entityId: contactId,
    metadata: { customerId },
  });

  revalidatePath(`/${orgSlug}/customers/${customerId}`);
  return { success: true };
}
