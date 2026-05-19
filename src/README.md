# Multi-Tenant CRM

A PerfexCRM-style multi-tenant CRM platform built with **Next.js 16.2**, TypeScript, Prisma (PostgreSQL), Auth.js v5, Tailwind CSS v4, BullMQ, and Stripe.

Every feature is delivered as a vertical slice across a clean, minimalist UI. Multi-tenancy is enforced at the application layer (`getOrgId()` / middleware) with optional Postgres Row-Level Security as a defence-in-depth layer.

---

## Quick start

### Prerequisites
- **Full Docker stack**: Docker + Docker Compose only (`Dockerfile` + `docker-compose.yml`).
- **App on host**: Node.js 20+, plus **Postgres** (e.g. [Supabase](https://supabase.com) local on port **54324** per `supabase/config.toml`, **`docker compose up -d postgres redis`**, or `docker-compose.db.yml` for tests on **5435**).

### 1. Choose how you run

**A - Full stack in Docker (Postgres, Redis, Next.js app, BullMQ workers)**

**On Dokploy (or any host):** in the compose / application environment, set **`AUTH_SECRET`**, **`AUTH_URL`**, and **`NEXT_PUBLIC_APP_URL`** for the services that run the app (apply the same values to **`app`** and **`workers`**). The compose file does **not** use `${AUTH_SECRET}` substitution, so the deploy step will not fail if those are only defined in the UI. Use the default **`DATABASE_URL`** / **`REDIS_URL`** in the file only if your services are named `postgres` and `redis` like this repo. Use **one** canonical **`AUTH_URL`** (no comma-separated values); the app trusts proxy headers (`trustHost`) for `platform.websitepuzzle.com`-style hosts.

**Locally:**

```bash
cp .env.docker.example .env.docker
# Edit AUTH_SECRET, AUTH_URL, NEXT_PUBLIC_APP_URL
docker compose up -d --build
```

(`.env.docker` is optional if you already inject the same variables another way.)

Open [http://localhost:3005/auth/login](http://localhost:3005/auth/login) (Docker Compose publishes the app on host port **3005**). Migrations run on `app` startup. Optional demo data:

```bash
docker compose exec app npx tsx prisma/seed.ts
```

**B - Postgres + Redis in Docker, Next.js on the machine (`npm run dev`)**

```bash
docker compose up -d postgres redis
```

Use `.env` from `.env.example`: `DATABASE_URL` → `localhost:5434`, `REDIS_URL` → `localhost:6381`.

**C - Supabase local (direct URL for `DATABASE_URL`)**

```text
postgresql://postgres:postgres@127.0.0.1:54324/postgres
```

Adjust user/password/port if your CLI prints different values in `supabase status`.

### 2. Install dependencies (host dev only)

```bash
npm install
```

### 3. Set up environment variables (host dev)

Skip this if you only use the **full Docker** path (A); that flow uses `.env.docker` instead.

```bash
cp .env.example .env
# Ensure DATABASE_URL matches your running Postgres (Supabase, or Docker on :5434)
```

### 4. Run database migrations (host dev)

Migrations are versioned in `prisma/migrations`. Apply them whenever you clone the repo or change branches:

```bash
npm run db:migrate
```

For CI or servers (non-interactive), use:

```bash
npm run db:deploy
```

### 5. (Optional) Apply RLS policies

```bash
npm run db:rls
```

### 6. Seed demo data (recommended for local dev)

```bash
npm run db:seed
```

This populates an `acme` organization with users, customers, projects, tasks, time entries, invoices in different statuses, payments, a signed contract, reminders, a customised email template and audit log entries. **Run this after migrations** so you have a ready-made test admin and full demo data.

**Login credentials after seed:**
- Staff (Admin): `admin@example.com` / `password123!` - use this as the default test admin (role `ADMIN`)
- Staff (Owner): `owner@example.com` / `password123!`
- Staff (PM): `pm@example.com` / `password123!`
- Portal contact (full access): `primary-contact@acme-customer.example` / `password123!`
- Portal contact (read-only): `viewer@acme-customer.example` / `password123!`

### 7. Start the dev server (Turbopack)

```bash
npm run dev
```

Visit [http://localhost:3000/auth/login](http://localhost:3000/auth/login).

### 8. (Optional) Start the worker process

```bash
npm run workers
```

Runs scheduled jobs in a separate process: FX refresh, recurring invoices, auto-billing, reminders, overdue invoice nudges, project status digests.

### Test database (optional isolated Postgres)

Use **`npm run docker:db`** when you want Postgres **only** on port **5435** (`crm_test`) so it does not clash with Supabase or the full Redis stack. By default, **`.env.test.example`** uses the same **Supabase direct** URL as `.env.example` (port `54324`); edit it if you use the DB-only container instead.

```bash
npm run docker:db          # optional: Postgres-only on :5435
cp .env.test.example .env.test
npm run db:test:setup
```

- With **Supabase** in `.env.test`: `migrate deploy` + seed against your local instance.
- With **docker:db**: point `DATABASE_URL` at `postgresql://crm:crm_secret@localhost:5435/crm_test` in `.env.test`.
- The setup script generates the client, runs **`migrate deploy`** when `prisma/migrations` exists, then **`prisma/seed.ts`** (`acme` org + demo users).

**Run Next.js against the test database** (same schema + seed users as dev):

```bash
cp .env.test.example .env.test   # adjust secrets if needed
npm run dev:test                  # loads .env.test then starts Next.js
```

If you merge variables into `.env` instead (point `DATABASE_URL` at `:5435` / `crm_test`), plain `npm run dev` works.

Stop the DB-only container:

```bash
npm run docker:db:down
```

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Framework | **Next.js 16.2** (App Router, Server Actions, Turbopack) |
| Language | TypeScript 5 |
| Database | PostgreSQL 16 + **Prisma ORM v7** |
| Auth | **Auth.js v5** (NextAuth) - credentials + magic-link + JWT |
| UI | Tailwind CSS v4 + custom shadcn/ui-style components |
| Queue | **BullMQ** + Redis 7 |
| State | TanStack Query (client), Server Actions (mutations) |
| Email | Nodemailer + per-tenant SMTP + MJML-style templates |
| SMS | Twilio (per-tenant credentials, optional) |
| PDF | `@react-pdf/renderer` for invoices, receipts, signed contracts |
| Payments | Stripe (per-tenant API keys, never platform-wide) |

---

## Architecture

### Multi-tenancy
Path-based routing - `/{orgSlug}/...` for staff, `/portal/{orgSlug}/...` for the customer portal. Subdomain routing can be added later without schema changes.

Every business table carries `organizationId`. The `getOrgId()` helper used in every Server Action verifies the current user's membership before mutating, and `proxy.ts` blocks any URL whose slug doesn't match the session's org. Optional Postgres RLS policies are provided in `prisma/sql/rls-policies.sql` for defence-in-depth.

```mermaid
flowchart LR
    Browser --> Mw["Next.js middleware"]
    Mw --> StaffApp["/app group"]
    Mw --> Portal["/portal group"]
    Mw --> Public["/auth, /sign/[token]"]
    StaffApp --> SA["Server Actions"]
    Portal --> SA
    SA --> Prisma --> PG[("Postgres + RLS")]
    SA --> Queue[("BullMQ + Redis")]
    Queue --> Workers["Workers"]
    SA --> Stripe
    SA --> SMTP
    SA --> Twilio
```

### Auth & roles
- Single `User` table with `userType: STAFF | CUSTOMER_CONTACT`
- Staff roles: `OWNER | ADMIN | PROJECT_MANAGER | STAFF`
- Customer-contact ACLs: per-flag `canSeeProjects | canSeeTasks | canSeeInvoices | canSeeContracts | canPayInvoices`
- Two route groups: `app/(staff)/[orgSlug]/...` and `app/(portal)/portal/[orgSlug]/...`

### Stripe (two layers)
1. **Per-tenant payment collection** - each tenant pastes their own Stripe secret key + webhook secret in Org Settings. The webhook endpoint is `/api/stripe/webhook/[orgSlug]`.
2. **Recurring subscriptions** - uses Stripe Subscriptions; synced via webhook events.
3. **Invoice payment** - Stripe Checkout session created on demand; success → Payment row → receipt PDF + email.

### Workers (BullMQ)
| Worker | Schedule | What it does |
|--------|----------|--------------|
| `fx-rates` | Daily 06:00 UTC | Refreshes EUR↔X rates per org |
| `recurring-invoice` | Daily 02:00 UTC | Materialises invoices from active recurring rules |
| `auto-billing` | Daily 03:00 UTC | Bundles unbilled time entries into draft invoices for projects with `autoInvoice=true` |
| `reminder` | Every minute | Fires due reminders via email/SMS/in-app |
| `overdue-invoice` | Daily 09:00 | Sends overdue reminders per `overdueReminderDays` setting |
| `status-email` | Daily 08:00 | Sends weekly/biweekly/monthly digest per project |

---

## Features

### Phase 1 - Foundation
Next.js 16.2 + Turbopack, Prisma + Postgres, Docker compose, Auth.js v5, org signup flow, path-based routing + middleware tenant guard.

### Phase 2 - Customers & Contacts
Customer CRUD (B2B/B2C, billing+shipping addresses, VAT, preferred currency), customer portal contacts with ACL flags, customer notes, org settings (display currency, FX provider sync, company info), daily FX rate worker.

### Phase 3 - Projects, Tasks & Time
Project CRUD (hourly/fixed billing, auto-invoice settings, status email cycle), task CRUD with assignees and due dates, manual time entry, start/stop timer with single-open-entry constraint, per-task hour summaries (logged / billed / paid).

### Phase 4 - Invoices & PDFs
Invoice CRUD with line items, currency + FX snapshot, VAT, autofill from selected customer, recurring invoice rules + worker, PDF generation, send via SMTP using editable email templates.

### Phase 5 - Stripe Payments + Manual Payments
Per-tenant Stripe keys, Stripe Checkout for invoice payment, webhook handler, manual payment recording (bank transfer / cash), receipt PDF auto-generated on PAID, Account Statement view per customer.

### Phase 6 - Stripe Subscriptions
Subscription model linked to Customer, create Price + Subscription via tenant's Stripe, sync via webhooks, surfaced in customer detail.

### Phase 7 - Auto-Billing
Daily worker turns unbilled time entries into draft (or sent) invoices on the project's schedule; transactional flag flip on TimeEntry to prevent double-billing; project status email digest worker.

### Phase 8 - Reminders & Overdue
Polymorphic Reminder entity (customer/project/task/invoice targets), notify-at scheduler with email/SMS/in-app channels, Twilio SMS integration, overdue-invoice reminder job with dedupe log, in-app notification center.

### Phase 9 - Contracts + In-App E-Signature
Rich-text contract editor with merge tags, send-for-signature flow with one-time tokenised public `/sign/[token]` page, signature pad + IP/UA/timestamp capture, signed PDF generation with audit panel, email signed PDF to both parties.

### Phase 10 - Customer Portal
Separate `/portal/[orgSlug]` route group, magic-link or password login for `CustomerContact`s, gated views (projects, tasks, invoices, receipts, contracts, account statement) per access flags, "Pay now" button → Stripe Checkout.

### Phase 11 - Editor, Filtering, Audit, RLS, Seeds
- **Email templates editor** at `/[orgSlug]/templates` - list of templates with "Customised" / "Default" badges; per-template editor with split-pane editing, live preview using sample merge-tag data, send-test, reset-to-default.
- **Advanced filtering** on every list view - search, date range, status, currency, customer multi-select. URL-based (shareable) and debounced. Powered by `lib/filters/` and `components/filters/filter-bar.tsx`.
- **CSV export** on every major list - invoices, customers, projects, payments, time entries. Each export reuses the active filter state.
- **Audit log** - `AuditLog` Prisma model + `lib/audit/log.ts` helper. Wired into customers, customer-contacts, invoices, payments, contracts (incl. signing), settings, templates, exports, sign-in/sign-out. Viewer at `/[orgSlug]/audit` with full filtering.
- **Postgres RLS** - `prisma/sql/rls-policies.sql` with soft policies on `Invoice`, `Payment`, `Contract`, `ContractSignature`, `TimeEntry`, `Reminder`, `Customer`, `AuditLog`. Use `withRls(orgId, fn)` from `lib/db/rls.ts` for strict per-request enforcement.
- **Seed data** - `npm run db:seed` populates a fully-loaded `acme` organization for instant demos.

---

## Project structure

```
app/
  auth/                              Login, signup, error pages (public)
  (staff)/[orgSlug]/                 Staff CRM - guarded by STAFF userType
    customers, projects, tasks
    time, invoices, payments
    contracts, reminders, notifications
    templates, audit, settings
    */export/route.ts                CSV export endpoints
  (portal)/portal/[orgSlug]/         Customer portal - guarded by CUSTOMER_CONTACT
  sign/[token]/                      Public e-signature page
  api/
    auth/[...nextauth]/              Auth.js handler
    stripe/webhook/[orgSlug]/        Per-tenant Stripe webhook
    pdf/{invoice|receipt|contract}/  PDF stream endpoints
    tasks/[taskId]/hours/            Task hour summary

components/
  ui/                                Button, Input, Card, Table, Dialog, Select, ...
  layout/                            Sidebar, Header
  customers/, projects/, time/,
  invoices/, settings/, contracts/,
  templates/, filters/

lib/
  db/                                Prisma client, withOrgContext, withRls
  auth/                              Auth.js config + login/logout audit events
  actions/                           "use server" mutations
  audit/                             logAudit helper
  filters/                           parseCommonFilters + CSV serialiser
  email/                             SMTP mailer + templates + template-keys
  pdf/                               @react-pdf/renderer documents
  sms/                               Twilio helper
  stripe/                            Per-tenant Stripe client factory
  workers/                           BullMQ queues + workers (FX, recurring, auto-billing, reminders, overdue, digest)
  utils/                             cn(), format()

prisma/
  schema.prisma                      Full data model
  seed.ts                            Demo data
  sql/rls-policies.sql               Optional RLS policies

scripts/
  workers.ts                         Standalone worker process entry
```

---

## Conventions

- All mutations live in `lib/actions/*.ts` as `"use server"` Server Actions.
- Always pass `organizationId` when querying; never trust client-provided IDs. Use `getOrgId(orgSlug)` for the standard authorization check.
- Use `revalidatePath()` after mutations.
- Time entries: `manualMinutes` OR (`startedAt` + `endedAt`) - never both.
- Only one running timer per user (no `endedAt`, has `startedAt`) - enforced inside `startTimer()`.
- Sensitive mutations should call `logAudit(...)` after the change so it shows up in `/[orgSlug]/audit`.
- List views read filters from `searchParams`; always parse via `parseCommonFilters(sp)` and translate into a Prisma `where`. Add an `export/route.ts` next to the page for CSV export.
- Email templates: each new email key MUST be added to `lib/email/template-keys.ts` (with sample vars) so the editor can preview/test it.

---

## Environment variables

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | Postgres connection |
| `REDIS_URL` | Redis for BullMQ workers and app rate limits - with this repo’s `docker compose`, use `redis://localhost:6381` |
| `AUTH_SECRET` | JWT signing secret |
| `AUTH_URL` / `NEXTAUTH_URL` | Public app URL (used for callback URLs) |
| `EMAIL_SERVER` | Magic-link mailer URL (Auth.js) |
| `EMAIL_FROM` | Magic-link `from` address |
| `EXCHANGERATE_API_KEY` | exchangerate.host access key (optional, FX worker) |
| `NEXT_PUBLIC_APP_URL` | Public app URL exposed to client |

Per-tenant secrets (Stripe keys, SMTP creds, Twilio creds) live in the `OrgSettings` row, NOT in env vars.

### Production Postgres and pooling

Use a **direct** `DATABASE_URL` for local development (as in `.env.example`). In production, especially with **serverless** or many Node instances, point `DATABASE_URL` at a pooler (for example **PgBouncer** or your host’s built-in pooler) in **transaction** mode so you do not exhaust Postgres connections. Follow [Prisma’s connection pooler guide](https://www.prisma.io/docs/guides/performance-and-optimization/connection-management#external-connection-poolers): add the query parameters your provider documents (commonly `?pgbouncer=true&connection_limit=1` for transaction poolers with the `pg` driver). Tune `connection_limit` with your pooler’s max client connections and replica count.
