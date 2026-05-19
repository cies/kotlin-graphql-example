-- Prevent duplicate receipt numbers within the same organization (PostgreSQL allows multiple NULLs).
CREATE UNIQUE INDEX "Payment_organizationId_receiptNumber_key" ON "Payment" ("organizationId", "receiptNumber");
