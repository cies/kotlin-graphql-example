-- Schema drift: models/columns in prisma/schema.prisma that never had migration SQL.
-- Idempotent where possible for partially-upgraded DBs.

-- Platform SaaS subscription (Org model orgSubscription)
DO $$ BEGIN
    CREATE TYPE "OrgSubscriptionStatus" AS ENUM ('ACTIVE', 'PAST_DUE', 'CANCELED', 'TRIALING', 'UNPAID');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS "OrgSubscription" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "stripeSubscriptionId" TEXT NOT NULL,
    "stripePriceId" TEXT,
    "stripeCustomerId" TEXT,
    "status" "OrgSubscriptionStatus" NOT NULL DEFAULT 'TRIALING',
    "currentPeriodEnd" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "OrgSubscription_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "OrgSubscription_organizationId_key" ON "OrgSubscription"("organizationId");
CREATE UNIQUE INDEX IF NOT EXISTS "OrgSubscription_stripeSubscriptionId_key" ON "OrgSubscription"("stripeSubscriptionId");

DO $$ BEGIN
    ALTER TABLE "OrgSubscription" ADD CONSTRAINT "OrgSubscription_organizationId_fkey"
      FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Staff team invites
CREATE TABLE IF NOT EXISTS "MemberInvite" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "role" "StaffRole" NOT NULL DEFAULT 'STAFF',
    "token" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "acceptedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MemberInvite_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "MemberInvite_token_key" ON "MemberInvite"("token");
CREATE INDEX IF NOT EXISTS "MemberInvite_organizationId_idx" ON "MemberInvite"("organizationId");
CREATE INDEX IF NOT EXISTS "MemberInvite_token_idx" ON "MemberInvite"("token");

DO $$ BEGIN
    ALTER TABLE "MemberInvite" ADD CONSTRAINT "MemberInvite_organizationId_fkey"
      FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Task audit log
CREATE TABLE IF NOT EXISTS "TaskActivity" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "taskId" TEXT NOT NULL,
    "userId" TEXT,
    "actorName" TEXT,
    "type" TEXT NOT NULL,
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "TaskActivity_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "TaskActivity_taskId_idx" ON "TaskActivity"("taskId");
CREATE INDEX IF NOT EXISTS "TaskActivity_organizationId_idx" ON "TaskActivity"("organizationId");

DO $$ BEGIN
    ALTER TABLE "TaskActivity" ADD CONSTRAINT "TaskActivity_taskId_fkey"
      FOREIGN KEY ("taskId") REFERENCES "Task"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE "TaskActivity" ADD CONSTRAINT "TaskActivity_userId_fkey"
      FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE "TaskActivity" ADD CONSTRAINT "TaskActivity_organizationId_fkey"
      FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Customer GDPR-style fields
ALTER TABLE "Customer" ADD COLUMN IF NOT EXISTS "anonymizedAt" TIMESTAMP(3);
ALTER TABLE "Customer" ADD COLUMN IF NOT EXISTS "privacyRequestedAt" TIMESTAMP(3);

-- Project semi-monthly auto-invoice fields
ALTER TABLE "Project" ADD COLUMN IF NOT EXISTS "semiMonthlyPeriodSplitDay" INTEGER;
ALTER TABLE "Project" ADD COLUMN IF NOT EXISTS "semiMonthlyEmitDay1" INTEGER;
ALTER TABLE "Project" ADD COLUMN IF NOT EXISTS "semiMonthlyEmitDay2" INTEGER;

-- Task override rate
ALTER TABLE "Task" ADD COLUMN IF NOT EXISTS "hourlyRate" DECIMAL(10,2);

-- Invoice public link + tax/discount/PDF fields (post-init evolution)
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "viewToken" TEXT;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "viewTokenCreatedAt" TIMESTAMP(3);
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "paymentMethod" "PaymentMethod";
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "template" "InvoiceTemplate";
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "vatIncluded" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "vatRate" DECIMAL(5,4) NOT NULL DEFAULT 0;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "periodFrom" TIMESTAMP(3);
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "periodTo" TIMESTAMP(3);
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "discountType" TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "discountValue" DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "discountBeforeTax" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "discount" DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "adminNote" TEXT;
ALTER TABLE "Invoice" ADD COLUMN IF NOT EXISTS "termsAndConditions" TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS "Invoice_viewToken_key" ON "Invoice"("viewToken");

-- Payments: receipt # on PDF
ALTER TABLE "Payment" ADD COLUMN IF NOT EXISTS "receiptNumber" TEXT;

-- Invoice lines: align with schema (name, qty type, task link, JSON metadata)
ALTER TABLE "InvoiceLine" ADD COLUMN IF NOT EXISTS "name" TEXT NOT NULL DEFAULT '';
ALTER TABLE "InvoiceLine" ADD COLUMN IF NOT EXISTS "qtyType" TEXT NOT NULL DEFAULT 'QTY';
ALTER TABLE "InvoiceLine" ADD COLUMN IF NOT EXISTS "taskId" TEXT;
ALTER TABLE "InvoiceLine" ADD COLUMN IF NOT EXISTS "metadata" JSONB;

DO $$ BEGIN
    ALTER TABLE "InvoiceLine" ALTER COLUMN "description" DROP NOT NULL;
EXCEPTION
    WHEN undefined_column THEN NULL;
END $$;
