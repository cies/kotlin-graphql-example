-- ─────────────────────────────────────────────────────────────────────────────
-- Optional Row-Level Security (RLS) policies for sensitive tables.
--
-- These policies are an additional defence-in-depth layer on top of the
-- application-level org scoping enforced by lib/db/with-org-context.ts and
-- the Server Actions in lib/actions/*.
--
-- The policies use a soft-enforcement pattern: when no `app.org_id` is set
-- on the session/transaction, queries continue to behave as before. When
-- `app.org_id` is set (e.g. via withRls() or a Prisma transaction that runs
-- `SET LOCAL app.org_id = '<id>'`), queries are restricted to rows whose
-- `organizationId` matches.
--
-- Apply once after `prisma migrate dev` (or wire into a custom migration):
--
--   psql "$DATABASE_URL" -f prisma/sql/rls-policies.sql
--
-- To enable strict enforcement (recommended in production), connect the app
-- as a non-superuser role that does not have the BYPASSRLS attribute, then
-- always wrap queries in withRls(orgId, fn) - see lib/db/rls.ts.
-- ─────────────────────────────────────────────────────────────────────────────

-- Helper function so policies can be authored once and reused.
CREATE OR REPLACE FUNCTION app_current_org_id()
RETURNS text AS $$
  SELECT NULLIF(current_setting('app.org_id', true), '');
$$ LANGUAGE SQL STABLE;

-- ─── Sensitive tables ────────────────────────────────────────────────────────
-- Each block: enable RLS, drop existing policy if any, create the soft policy.

-- Invoice
ALTER TABLE "Invoice" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS invoice_org_isolation ON "Invoice";
CREATE POLICY invoice_org_isolation ON "Invoice"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- Payment
ALTER TABLE "Payment" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payment_org_isolation ON "Payment";
CREATE POLICY payment_org_isolation ON "Payment"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- Contract
ALTER TABLE "Contract" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS contract_org_isolation ON "Contract";
CREATE POLICY contract_org_isolation ON "Contract"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- ContractSignature (no organizationId column → join via contract)
ALTER TABLE "ContractSignature" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS contract_signature_org_isolation ON "ContractSignature";
CREATE POLICY contract_signature_org_isolation ON "ContractSignature"
  USING (
    app_current_org_id() IS NULL
    OR EXISTS (
      SELECT 1 FROM "Contract" c
      WHERE c.id = "ContractSignature"."contractId"
        AND c."organizationId" = app_current_org_id()
    )
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR EXISTS (
      SELECT 1 FROM "Contract" c
      WHERE c.id = "ContractSignature"."contractId"
        AND c."organizationId" = app_current_org_id()
    )
  );

-- TimeEntry
ALTER TABLE "TimeEntry" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS time_entry_org_isolation ON "TimeEntry";
CREATE POLICY time_entry_org_isolation ON "TimeEntry"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- Reminder
ALTER TABLE "Reminder" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS reminder_org_isolation ON "Reminder";
CREATE POLICY reminder_org_isolation ON "Reminder"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- Customer
ALTER TABLE "Customer" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS customer_org_isolation ON "Customer";
CREATE POLICY customer_org_isolation ON "Customer"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- CustomerNotificationEmail
ALTER TABLE "CustomerNotificationEmail" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS customer_notification_email_org_isolation ON "CustomerNotificationEmail";
CREATE POLICY customer_notification_email_org_isolation ON "CustomerNotificationEmail"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );

-- AuditLog
ALTER TABLE "AuditLog" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS audit_log_org_isolation ON "AuditLog";
CREATE POLICY audit_log_org_isolation ON "AuditLog"
  USING (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  )
  WITH CHECK (
    app_current_org_id() IS NULL
    OR "organizationId" = app_current_org_id()
  );
