-- CreateEnum
CREATE TYPE "CustomerNotificationPurpose" AS ENUM ('INVOICES', 'DIGEST', 'CONTRACTS');

-- CreateTable
CREATE TABLE "CustomerNotificationEmail" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "customerId" TEXT NOT NULL,
    "purpose" "CustomerNotificationPurpose" NOT NULL,
    "email" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CustomerNotificationEmail_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "CustomerNotificationEmail_organizationId_idx" ON "CustomerNotificationEmail"("organizationId");

-- CreateIndex
CREATE INDEX "CustomerNotificationEmail_customerId_purpose_idx" ON "CustomerNotificationEmail"("customerId", "purpose");

-- CreateIndex
CREATE UNIQUE INDEX "CustomerNotificationEmail_customerId_purpose_email_key" ON "CustomerNotificationEmail"("customerId", "purpose", "email");

-- AddForeignKey
ALTER TABLE "CustomerNotificationEmail" ADD CONSTRAINT "CustomerNotificationEmail_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CustomerNotificationEmail" ADD CONSTRAINT "CustomerNotificationEmail_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "Customer"("id") ON DELETE CASCADE ON UPDATE CASCADE;
