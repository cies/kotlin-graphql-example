-- Backfill missing issue dates from row creation time (stable for historical data)
UPDATE "Invoice" SET "issuedAt" = "createdAt" WHERE "issuedAt" IS NULL;

-- Require issuedAt on all rows; default for new inserts
ALTER TABLE "Invoice" ALTER COLUMN "issuedAt" SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "Invoice" ALTER COLUMN "issuedAt" SET NOT NULL;
