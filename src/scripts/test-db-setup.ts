/**
 * Migrate schema + demo seed against DATABASE_URL from .env.test
 * (typically Postgres-only Docker on port 5435).
 *
 * Run: npm run db:test:setup
 */
import { config } from "dotenv";
import { execSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { resolve } from "node:path";

const envPath = resolve(process.cwd(), ".env.test");

if (!existsSync(envPath)) {
  console.error(`Create ${envPath} first (copy from .env.test.example).`);
  process.exit(1);
}

config({ path: envPath, override: true });

if (!process.env.DATABASE_URL) {
  console.error(".env.test must define DATABASE_URL.");
  process.exit(1);
}

const env = { ...process.env } as NodeJS.ProcessEnv;

function run(label: string, cmd: string) {
  console.log(`→ ${label}…`);
  execSync(cmd, { stdio: "inherit", env });
}

function hasSqlMigrations(): boolean {
  const dir = resolve(process.cwd(), "prisma", "migrations");
  if (!existsSync(dir)) return false;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (existsSync(resolve(dir, entry.name, "migration.sql"))) return true;
  }
  return false;
}

run("Prisma generate", "npx prisma generate");

if (hasSqlMigrations()) {
  run("Prisma migrate deploy", "npx prisma migrate deploy");
} else {
  run("Prisma db push", "npx prisma db push");
}

run("Seed demo data", "npx tsx prisma/seed.ts");

console.log("\n✓ Test database schema applied and seeded (org slug: acme).");
console.log("  Staff admin: admin@example.com / password123!\n");
