/**
 * Demo seed for the Multi-Tenant CRM.
 *
 * Creates a fully populated organization so every screen has data to render:
 *  - 1 organization + owner + admin + PM + platform admin (Alex) staff users
 *  - 1 B2B customer with 2 portal contacts (different ACL flags)
 *  - 1 B2C customer
 *  - 2 projects (hourly + fixed) with tasks
 *  - Time entries (manual + start/stop), some billed
 *  - Invoices (DRAFT, SENT, PAID, OVERDUE) with payments + receipts
 *  - 1 signed contract with signature audit
 *  - Reminders + customised email template + sample audit log entries
 *
 * Run with:
 *   npx tsx prisma/seed.ts
 *
 * Re-running is idempotent on the org slug: existing seed data is deleted
 * and re-created so the demo always reflects the latest schema.
 */
import { config as loadEnv } from "dotenv";
import { resolve } from "node:path";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import bcrypt from "bcryptjs";
import { getDefaultTemplates } from "../lib/email/templates";

// Same as Next/Prisma CLI behaviour: standalone `tsx prisma/seed.ts` does not load `.env` unless we do.
const root = process.cwd();
loadEnv({ path: resolve(root, ".env") });
loadEnv({ path: resolve(root, ".env.local"), override: true });

function createPrisma(): PrismaClient {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    throw new Error(
      'DATABASE_URL is not set. Add it to `.env` (see `.env.example`) or run with: DATABASE_URL="postgresql://..." npm run db:seed',
    );
  }
  return new PrismaClient({
    adapter: new PrismaPg({ connectionString }),
  });
}

const prisma = createPrisma();

const ORG_SLUG = "acme";
const ORG_NAME = "Acme Studio";

const ADMIN_EMAIL = "admin@example.com";
const ADMIN_PASSWORD = "password123!";

const OWNER_EMAIL = "owner@example.com";
const OWNER_PASSWORD = "password123!";

const PM_EMAIL = "pm@example.com";
const PM_PASSWORD = "password123!";

/** Platform operator seed - must match PLATFORM_ADMIN_EMAILS for /admin access. */
const PLATFORM_ADMIN_EMAIL = "alex@websitepuzzle.com";
const PLATFORM_ADMIN_PASSWORD = "password123!";

const PORTAL_PRIMARY_EMAIL = "primary-contact@acme-customer.example";
const PORTAL_PRIMARY_PASSWORD = "password123!";

const PORTAL_VIEWER_EMAIL = "viewer@acme-customer.example";
const PORTAL_VIEWER_PASSWORD = "password123!";

async function main() {
  console.log(`→ Seeding org "${ORG_SLUG}" with full demo data...`);

  // 1. Wipe any prior org with this slug (cascades to all related data)
  const existing = await prisma.organization.findUnique({
    where: { slug: ORG_SLUG },
  });
  if (existing) {
    console.log("  · removing previous demo org");
    await prisma.organization.delete({ where: { id: existing.id } });
    // Best-effort: orphan demo users from prior seeds
    await prisma.user.deleteMany({
      where: {
        email: {
          in: [
            ADMIN_EMAIL,
            OWNER_EMAIL,
            PM_EMAIL,
            PLATFORM_ADMIN_EMAIL,
            PORTAL_PRIMARY_EMAIL,
            PORTAL_VIEWER_EMAIL,
          ],
        },
      },
    });
  }

  // 2. Org + owner + settings
  const ownerHash = await bcrypt.hash(OWNER_PASSWORD, 12);
  const pmHash = await bcrypt.hash(PM_PASSWORD, 12);
  const adminHash = await bcrypt.hash(ADMIN_PASSWORD, 12);
  const platformAdminHash = await bcrypt.hash(PLATFORM_ADMIN_PASSWORD, 12);

  const org = await prisma.organization.create({
    data: {
      name: ORG_NAME,
      slug: ORG_SLUG,
      settings: {
        create: {
          displayCurrency: "EUR",
          overdueReminderDays: [3, 7, 14, 30],
          autoSendInvoice: false,
          vatRate: "0.21",
          companyName: ORG_NAME,
          companyAddress: "123 Studio Street, Bucharest, Romania",
          companyVat: "RO12345678",
          companyPhone: "US: +1 555-0100\nEU: +40 21 555 0100",
          smtpFrom: "no-reply@acme.example",
          emailEnvelopeFooterLine:
            "PUZZLE WEBSITE SOLUTIONS LLC, 15442 Ventura Blvd., Ste 201-039, Los Angeles, California, 91403, US",
        },
      },
    },
  });

  const owner = await prisma.user.create({
    data: {
      email: OWNER_EMAIL,
      name: "Demo Owner",
      passwordHash: ownerHash,
      userType: "STAFF",
    },
  });
  const pm = await prisma.user.create({
    data: {
      email: PM_EMAIL,
      name: "Demo PM",
      passwordHash: pmHash,
      userType: "STAFF",
    },
  });

  const adminUser = await prisma.user.create({
    data: {
      email: ADMIN_EMAIL,
      name: "Test Admin",
      passwordHash: adminHash,
      userType: "STAFF",
    },
  });

  const platformAdminUser = await prisma.user.create({
    data: {
      email: PLATFORM_ADMIN_EMAIL,
      name: "Alex (Platform admin)",
      passwordHash: platformAdminHash,
      userType: "STAFF",
    },
  });

  await prisma.organizationMember.createMany({
    data: [
      { organizationId: org.id, userId: owner.id, role: "OWNER" },
      { organizationId: org.id, userId: adminUser.id, role: "ADMIN" },
      { organizationId: org.id, userId: pm.id, role: "PROJECT_MANAGER" },
      { organizationId: org.id, userId: platformAdminUser.id, role: "ADMIN" },
    ],
  });

  console.log("  · org + owner + admin + pm + platform admin (staff)");

  // 3. FX rates (just one demo row so the rate page isn't empty)
  await prisma.fxRate.create({
    data: {
      organizationId: org.id,
      baseCurrency: "EUR",
      quoteCurrency: "USD",
      rate: "1.0820",
    },
  });

  // 4. B2B customer with portal contacts
  const b2b = await prisma.customer.create({
    data: {
      organizationId: org.id,
      type: "B2B",
      companyName: "Northwind Logistics",
      email: "billing@northwind.example",
      phone: "+40 720 000 111",
      vat: "RO87654321",
      preferredCurrency: "EUR",
      billingAddress: {
        line1: "Strada Industriei 12",
        city: "Cluj-Napoca",
        postalCode: "400000",
        country: "Romania",
      },
      shippingAddress: {
        line1: "Strada Industriei 12",
        city: "Cluj-Napoca",
        postalCode: "400000",
        country: "Romania",
      },
      notes: "Strategic account. Quarterly business reviews.",
    },
  });

  const primaryHash = await bcrypt.hash(PORTAL_PRIMARY_PASSWORD, 12);
  const viewerHash = await bcrypt.hash(PORTAL_VIEWER_PASSWORD, 12);

  const primaryUser = await prisma.user.create({
    data: {
      email: PORTAL_PRIMARY_EMAIL,
      name: "Anna Primary",
      passwordHash: primaryHash,
      userType: "CUSTOMER_CONTACT",
    },
  });
  const viewerUser = await prisma.user.create({
    data: {
      email: PORTAL_VIEWER_EMAIL,
      name: "Bob Viewer",
      passwordHash: viewerHash,
      userType: "CUSTOMER_CONTACT",
    },
  });

  await prisma.customerContact.createMany({
    data: [
      {
        organizationId: org.id,
        customerId: b2b.id,
        userId: primaryUser.id,
        isPrimary: true,
        canSeeProjects: true,
        canSeeTasks: true,
        canSeeInvoices: true,
        canSeeContracts: true,
        canPayInvoices: true,
      },
      {
        organizationId: org.id,
        customerId: b2b.id,
        userId: viewerUser.id,
        isPrimary: false,
        canSeeProjects: true,
        canSeeTasks: false,
        canSeeInvoices: true,
        canSeeContracts: false,
        canPayInvoices: false,
      },
    ],
  });

  await prisma.customerNote.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      authorId: owner.id,
      content: "Renewal call scheduled for next quarter.",
    },
  });

  // 5. B2C customer
  const b2c = await prisma.customer.create({
    data: {
      organizationId: org.id,
      type: "B2C",
      firstName: "Maria",
      lastName: "Popescu",
      email: "maria.popescu@example.com",
      phone: "+40 720 222 333",
      preferredCurrency: "EUR",
      billingAddress: {
        line1: "Bd. Carol I 88",
        city: "Bucharest",
        postalCode: "020000",
        country: "Romania",
      },
    },
  });

  console.log("  · 2 customers (B2B with 2 contacts + B2C)");

  // 6. Projects + tasks + time entries
  const websiteProject = await prisma.project.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      name: "Northwind Website Redesign",
      description: "Full visual + checkout overhaul",
      billingMode: "HOURLY",
      hourlyRate: "85.00",
      currency: "EUR",
      autoInvoice: true,
      autoInvoiceDay: 1,
      autoInvoiceCycle: "MONTHLY",
      statusEmailCycle: "WEEKLY",
      status: "ACTIVE",
    },
  });

  const brandingProject = await prisma.project.create({
    data: {
      organizationId: org.id,
      customerId: b2c.id,
      name: "Personal Brand Identity",
      billingMode: "FIXED",
      fixedFee: "1800.00",
      currency: "EUR",
      status: "ACTIVE",
    },
  });

  const homepageTask = await prisma.task.create({
    data: {
      organizationId: org.id,
      projectId: websiteProject.id,
      title: "Homepage v2 design",
      description: "Hero, social proof, pricing teaser",
      status: "DONE",
      assigneeIds: [pm.id],
      estimatedHours: "12",
    },
  });

  const checkoutTask = await prisma.task.create({
    data: {
      organizationId: org.id,
      projectId: websiteProject.id,
      title: "Checkout flow migration",
      status: "IN_PROGRESS",
      assigneeIds: [owner.id, pm.id],
      estimatedHours: "20",
      dueDate: new Date(Date.now() + 1000 * 60 * 60 * 24 * 7),
    },
  });

  const logoTask = await prisma.task.create({
    data: {
      organizationId: org.id,
      projectId: brandingProject.id,
      title: "Logo concepts",
      status: "REVIEW",
      assigneeIds: [pm.id],
      estimatedHours: "8",
    },
  });

  // Time entries: a mix of billed + unbilled
  const now = Date.now();
  const utcCalendarDay = (offsetDays: number) => {
    const d = new Date(now);
    return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - offsetDays));
  };
  const checkoutTimerStartedAt = new Date(now - 1000 * 60 * 60 * 4);
  const d40 = utcCalendarDay(40);
  const d38 = utcCalendarDay(38);
  const d2 = utcCalendarDay(2);
  const d5 = utcCalendarDay(5);
  await prisma.timeEntry.createMany({
    data: [
      {
        organizationId: org.id,
        taskId: homepageTask.id,
        userId: pm.id,
        manualMinutes: 360,
        loggedDate: d40,
        workSortAt: d40,
        description: "Homepage hero + variants",
        billed: true,
        hourlyRateSnapshot: "85.00",
      },
      {
        organizationId: org.id,
        taskId: homepageTask.id,
        userId: pm.id,
        manualMinutes: 240,
        loggedDate: d38,
        workSortAt: d38,
        description: "Stakeholder review + revisions",
        billed: true,
        hourlyRateSnapshot: "85.00",
      },
      {
        organizationId: org.id,
        taskId: checkoutTask.id,
        userId: owner.id,
        startedAt: checkoutTimerStartedAt,
        endedAt: new Date(now - 1000 * 60 * 60 * 1),
        workSortAt: checkoutTimerStartedAt,
        description: "Stripe Elements migration",
        billed: false,
        hourlyRateSnapshot: "85.00",
      },
      {
        organizationId: org.id,
        taskId: checkoutTask.id,
        userId: pm.id,
        manualMinutes: 90,
        loggedDate: d2,
        workSortAt: d2,
        description: "QA on staging",
        billed: false,
        hourlyRateSnapshot: "85.00",
      },
      {
        organizationId: org.id,
        taskId: logoTask.id,
        userId: pm.id,
        manualMinutes: 180,
        loggedDate: d5,
        workSortAt: d5,
        description: "Initial sketches",
        billed: false,
      },
    ],
  });

  console.log("  · 2 projects, 3 tasks, 5 time entries");

  // 7. Invoices in various statuses
  const inv1 = await prisma.invoice.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      number: "INV-2026-0001",
      status: "PAID",
      currency: "EUR",
      subtotal: "850.00",
      vat: "178.50",
      total: "1028.50",
      amountPaid: "1028.50",
      issuedAt: new Date(now - 1000 * 60 * 60 * 24 * 35),
      dueDate: new Date(now - 1000 * 60 * 60 * 24 * 5),
      paidAt: new Date(now - 1000 * 60 * 60 * 24 * 6),
      lines: {
        create: [
          {
            description: "Homepage v2 design - 10h × €85",
            quantity: "10",
            unitPrice: "85",
            total: "850.00",
            sortOrder: 0,
          },
        ],
      },
      payments: {
        create: {
          organizationId: org.id,
          method: "STRIPE",
          amount: "1028.50",
          currency: "EUR",
          paidAt: new Date(now - 1000 * 60 * 60 * 24 * 6),
          stripeChargeId: "ch_demo_paid_001",
        },
      },
    },
  });

  const inv2 = await prisma.invoice.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      number: "INV-2026-0002",
      status: "OVERDUE",
      currency: "EUR",
      subtotal: "1700.00",
      vat: "357.00",
      total: "2057.00",
      amountPaid: "0",
      issuedAt: new Date(now - 1000 * 60 * 60 * 24 * 45),
      dueDate: new Date(now - 1000 * 60 * 60 * 24 * 15),
      lines: {
        create: [
          {
            description: "Checkout flow migration - first milestone",
            quantity: "20",
            unitPrice: "85",
            total: "1700.00",
            sortOrder: 0,
          },
        ],
      },
    },
  });

  const inv3 = await prisma.invoice.create({
    data: {
      organizationId: org.id,
      customerId: b2c.id,
      number: "INV-2026-0003",
      status: "SENT",
      currency: "EUR",
      subtotal: "900.00",
      vat: "189.00",
      total: "1089.00",
      amountPaid: "0",
      issuedAt: new Date(now - 1000 * 60 * 60 * 24 * 3),
      dueDate: new Date(now + 1000 * 60 * 60 * 24 * 27),
      lines: {
        create: [
          {
            description: "Personal Brand Identity - 50% deposit",
            quantity: "1",
            unitPrice: "900",
            total: "900.00",
            sortOrder: 0,
          },
        ],
      },
    },
  });

  const inv4 = await prisma.invoice.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      number: "INV-2026-0004",
      status: "DRAFT",
      currency: "USD",
      fxRateToOrgCurrency: "1.0820",
      subtotal: "500.00",
      vat: "0",
      total: "500.00",
      amountPaid: "0",
      lines: {
        create: [
          {
            description: "Performance audit (USD currency demo)",
            quantity: "1",
            unitPrice: "500",
            total: "500.00",
            sortOrder: 0,
          },
        ],
      },
    },
  });

  console.log("  · 4 invoices (PAID, OVERDUE, SENT, DRAFT) + 1 payment");

  // 8. Recurring rule
  await prisma.recurringInvoiceRule.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      interval: "MONTHLY",
      nextRunAt: new Date(now + 1000 * 60 * 60 * 24 * 28),
      active: true,
      templateData: {
        currency: "EUR",
        vatRate: "0.21",
        daysUntilDue: 30,
        notes: "Monthly retainer.",
        lines: [
          { description: "Monthly retainer", quantity: "1", unitPrice: "1500", sortOrder: 0 },
        ],
      },
    },
  });

  // 9. Subscription (demo, no real Stripe wiring)
  await prisma.subscription.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      status: "active",
      stripePriceId: "price_demo_retainer",
      currentPeriodStart: new Date(now - 1000 * 60 * 60 * 24 * 5),
      currentPeriodEnd: new Date(now + 1000 * 60 * 60 * 24 * 25),
    },
  });

  // 10. Contract (signed)
  const contract = await prisma.contract.create({
    data: {
      organizationId: org.id,
      customerId: b2b.id,
      title: "Master Services Agreement 2026",
      status: "SIGNED",
      bodyHtml: `
        <h1>Master Services Agreement</h1>
        <p>Between <strong>{{org.name}}</strong> and <strong>{{customer.companyName}}</strong>.</p>
        <h2>1. Scope</h2>
        <p>Service provider will provide design and engineering services as agreed in individual statements of work.</p>
        <h2>2. Payment terms</h2>
        <p>Net 30 unless otherwise agreed.</p>
        <h2>3. Confidentiality</h2>
        <p>Both parties agree to keep all shared information confidential.</p>
      `,
    },
  });
  await prisma.contractSignature.create({
    data: {
      contractId: contract.id,
      signerName: "Anna Primary",
      signerEmail: PORTAL_PRIMARY_EMAIL,
      signatureSvg:
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 80"><path d="M10 60 Q 75 10, 150 50 T 290 30" stroke="#0f172a" stroke-width="2" fill="none"/></svg>',
      ipAddress: "203.0.113.45",
      userAgent:
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15",
      signedAt: new Date(now - 1000 * 60 * 60 * 24 * 30),
    },
  });

  // 11. Reminders + notifications
  await prisma.reminder.create({
    data: {
      organizationId: org.id,
      targetType: "CUSTOMER",
      targetId: b2b.id,
      title: "Quarterly business review",
      description: "Send agenda + last-quarter metrics",
      notifyAt: new Date(now + 1000 * 60 * 60 * 24 * 7),
      channels: ["EMAIL", "INAPP"],
      recipientIds: [owner.id, pm.id],
    },
  });

  await prisma.reminder.create({
    data: {
      organizationId: org.id,
      targetType: "INVOICE",
      targetId: inv2.id,
      title: "Chase overdue invoice INV-2026-0002",
      description: "Northwind hasn't paid; nudge with phone call.",
      notifyAt: new Date(now + 1000 * 60 * 60 * 24 * 1),
      channels: ["EMAIL", "INAPP"],
      recipientIds: [owner.id],
    },
  });

  await prisma.notification.create({
    data: {
      organizationId: org.id,
      userId: owner.id,
      title: "Welcome to your CRM",
      body: "Demo data has been loaded. Sign in as different users to test the portal.",
      link: `/${ORG_SLUG}`,
    },
  });

  const defaultInvoiceEmail = getDefaultTemplates()["invoice.sent"];

  // 12. Customised email template (so the editor shows a "Customised" badge)
  await prisma.emailTemplate.create({
    data: {
      organizationId: org.id,
      key: "invoice.sent",
      subject: defaultInvoiceEmail.subject,
      bodyMjml: defaultInvoiceEmail.bodyMjml,
    },
  });

  // 13. A handful of audit log entries to populate the audit page
  await prisma.auditLog.createMany({
    data: [
      {
        organizationId: org.id,
        userId: owner.id,
        actorEmail: owner.email,
        actorType: "STAFF",
        action: "CREATE",
        entityType: "CUSTOMER",
        entityId: b2b.id,
        metadata: { type: "B2B", name: "Northwind Logistics" },
      },
      {
        organizationId: org.id,
        userId: owner.id,
        actorEmail: owner.email,
        actorType: "STAFF",
        action: "CREATE",
        entityType: "INVOICE",
        entityId: inv1.id,
        metadata: { number: inv1.number, total: "1028.50" },
      },
      {
        organizationId: org.id,
        userId: owner.id,
        actorEmail: owner.email,
        actorType: "STAFF",
        action: "SEND",
        entityType: "INVOICE",
        entityId: inv1.id,
        metadata: { to: b2b.email },
      },
      {
        organizationId: org.id,
        userId: null,
        actorEmail: null,
        actorType: "STRIPE_WEBHOOK",
        action: "PAY",
        entityType: "PAYMENT",
        metadata: { invoiceNumber: inv1.number, amount: "1028.50", method: "STRIPE" },
      },
      {
        organizationId: org.id,
        actorEmail: PORTAL_PRIMARY_EMAIL,
        actorType: "EXTERNAL_SIGNER",
        action: "SIGN",
        entityType: "CONTRACT",
        entityId: contract.id,
        ipAddress: "203.0.113.45",
        metadata: { signerName: "Anna Primary" },
      },
    ],
  });

  console.log("  · contract, reminders, notification, custom template, audit entries");
  console.log("");
  console.log("✓ Seed complete!");
  console.log("");
  console.log("Sign in URLs:");
  console.log(`  Staff app  →  http://localhost:3000/auth/login`);
  console.log(`              ${OWNER_EMAIL} / ${OWNER_PASSWORD}`);
  console.log(`              ${ADMIN_EMAIL} / ${ADMIN_PASSWORD} (ADMIN)`);
  console.log(`              ${PM_EMAIL} / ${PM_PASSWORD}`);
  console.log(`  Customer portal  →  http://localhost:3000/portal/${ORG_SLUG}/login`);
  console.log(`              ${PORTAL_PRIMARY_EMAIL} / ${PORTAL_PRIMARY_PASSWORD}`);
  console.log(`              ${PORTAL_VIEWER_EMAIL} / ${PORTAL_VIEWER_PASSWORD}`);
}

main()
  .catch((err) => {
    console.error(err);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
